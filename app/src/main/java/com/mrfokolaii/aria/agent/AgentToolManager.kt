package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.mcp.McpClient
import com.mrfokolaii.aria.agent.mcp.McpRemoteTool
import com.mrfokolaii.aria.agent.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AgentToolManager(private val context: android.content.Context? = null) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val registry = ToolRegistry()
    private val router = ToolRouter(registry)

    private val enhancedAccessibility = EnhancedAccessibilityTool()
    private val mcpLoaded = AtomicBoolean(false)
    @Volatile private var loadedMcpUrl: String = ""

    init {
        registerAllTools()
    }

    fun setAccessibilityService(service: android.accessibilityservice.AccessibilityService?) {
        enhancedAccessibility.setService(service)
    }

    private fun registerAllTools() {
        fun safeRegister(tool: AgentTool?) {
            if (tool != null) {
                try {
                    registry.register(tool)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register tool: ${e.message}")
                }
            }
        }

        safeRegister(WebSearchTool(client))
        context?.let { safeRegister(WebBrowseTool(it, client)) }
        context?.let { safeRegister(FileDownloadTool(it, client)) }
        safeRegister(GitHubTool(client, context))
        safeRegister(YouTubeTool(client))
        safeRegister(RedditTool(client))
        safeRegister(InstagramTool(client))
        safeRegister(BilibiliTool(client))
        safeRegister(RSSReaderTool(client))

        context?.let { ctx ->
            safeRegister(PhoneTool(ctx))
            safeRegister(SMSTool(ctx))
            safeRegister(AppLauncherTool(ctx))
            safeRegister(ScreenReaderTool())
            safeRegister(enhancedAccessibility)
            safeRegister(BaleTool(ctx))
            safeRegister(EitaaTool(ctx))
            safeRegister(RubikaTool(ctx))
            safeRegister(WhatsAppTool(ctx))
            safeRegister(TelegramTool(ctx))
            safeRegister(InstagramAppTool(ctx))
        }

        Log.d(TAG, "Registered ${registry.getAllTools().size} tools")
    }

    fun invalidateMcpCache() {
        mcpLoaded.set(false)
        loadedMcpUrl = ""
        registry.unregisterByPrefix("mcp_")
    }

    /**
     * Load remote MCP tools when enabled in prefs.
     * Prefs: mcp_enabled=true, mcp_server_url=https://host/mcp
     */
    suspend fun ensureMcpToolsLoaded(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext 0
        val prefs = ctx.getSharedPreferences("aria", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(McpClient.PREF_ENABLED, false)
        val url = prefs.getString(McpClient.PREF_URL, "")?.trim().orEmpty()

        if (!enabled || url.isBlank()) {
            registry.unregisterByPrefix("mcp_")
            mcpLoaded.set(true)
            loadedMcpUrl = ""
            return@withContext 0
        }

        if (!force && mcpLoaded.get() && loadedMcpUrl == url) {
            return@withContext registry.getAllTools().count { it.name.startsWith("mcp_") }
        }

        registry.unregisterByPrefix("mcp_")
        try {
            val mcp = McpClient(url, client)
            val remoteTools = mcp.listTools()
            var count = 0
            for (info in remoteTools) {
                try {
                    registry.register(
                        McpRemoteTool(mcp, info.name, info.description, info.inputSchema)
                    )
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "MCP register ${info.name}: ${e.message}")
                }
            }
            mcpLoaded.set(true)
            loadedMcpUrl = url
            Log.i(TAG, "MCP loaded $count tools from $url")
            count
        } catch (e: Exception) {
            Log.e(TAG, "MCP load failed", e)
            mcpLoaded.set(true)
            loadedMcpUrl = url
            0
        }
    }

    suspend fun executeToolByName(toolName: String, params: Map<String, Any>): ToolResult {
        val tool = registry.getTool(toolName)
            ?: return ToolResult(false, null, "Tool not found: $toolName")
        return tool.execute(params)
    }

    suspend fun routeRequest(userRequest: String, params: Map<String, Any> = emptyMap()): ToolResult {
        return router.route(userRequest, params)
    }

    fun getRegistry(): ToolRegistry = registry

    fun getRouter(): ToolRouter = router

    fun listAllTools(): List<Map<String, Any>> {
        return registry.getAllTools().map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "category" to tool.category.name
            )
        }
    }

    fun listMcpTools(): List<String> =
        registry.getAllTools().filter { it.name.startsWith("mcp_") }.map { it.name }

    suspend fun runDoctorCheck(): DoctorReport = withContext(Dispatchers.IO) {
        ensureMcpToolsLoaded()
        val healthResults = registry.checkAllHealth()
        val healthy = healthResults.filter { it.value.isHealthy }
        val unhealthy = healthResults.filter { !it.value.isHealthy }

        DoctorReport(
            totalTools = registry.getAllTools().size,
            healthyTools = healthy.size,
            unhealthyTools = unhealthy.size,
            healthDetails = healthResults,
            recommendations = generateRecommendations(unhealthy)
        )
    }

    private fun generateRecommendations(unhealthy: Map<String, HealthStatus>): List<String> {
        val recommendations = mutableListOf<String>()
        unhealthy.forEach { (toolName, status) ->
            when {
                status.message.contains("authentication", ignoreCase = true) ->
                    recommendations.add("$toolName: Configure API credentials")
                status.message.contains("timeout", ignoreCase = true) ->
                    recommendations.add("$toolName: Check network connection")
                else -> recommendations.add("$toolName: ${status.message}")
            }
        }
        return recommendations
    }

    companion object {
        private const val TAG = "AgentToolManager"
    }
}

data class DoctorReport(
    val totalTools: Int,
    val healthyTools: Int,
    val unhealthyTools: Int,
    val healthDetails: Map<String, HealthStatus>,
    val recommendations: List<String>
) {
    fun toReadableString(): String {
        return buildString {
            appendLine("ARIA Tools: $healthyTools/$totalTools healthy")
            healthDetails.forEach { (name, status) ->
                val icon = if (status.isHealthy) "OK" else "ERR"
                appendLine("  $icon $name: ${status.message}")
            }
        }
    }
}
