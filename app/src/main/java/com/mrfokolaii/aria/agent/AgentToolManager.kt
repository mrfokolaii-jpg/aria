package com.mrfokolaii.aria.agent

import android.util.Log
import com.mrfokolaii.aria.agent.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentToolManager(private val context: android.content.Context? = null) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val registry = ToolRegistry()
    private val router = ToolRouter(registry)

    // MUST be initialized before registerAllTools() / init block
    private val enhancedAccessibility = EnhancedAccessibilityTool()

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
        safeRegister(WebBrowseTool(client))
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

    fun listAllTools(): List<AgentTool> = registry.getAllTools()

    suspend fun runDoctorCheck(): DoctorReport = withContext(Dispatchers.IO) {
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
                status.message.contains("404", ignoreCase = true) ->
                    recommendations.add("$toolName: Service endpoint may have changed")
                else ->
                    recommendations.add("$toolName: ${status.message}")
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
            appendLine("🔧 ARIA Agent Tools Health Report")
            appendLine("=".repeat(50))
            appendLine("Total Tools: $totalTools")
            appendLine("✅ Healthy: $healthyTools")
            appendLine("❌ Unhealthy: $unhealthyTools")
            appendLine()
            appendLine("Details:")
            healthDetails.forEach { (name, status) ->
                val icon = if (status.isHealthy) "✅" else "❌"
                appendLine("  $icon $name: ${status.message} (${status.latencyMs}ms)")
            }
            if (recommendations.isNotEmpty()) {
                appendLine()
                appendLine("Recommendations:")
                recommendations.forEach { appendLine("  • $it") }
            }
        }
    }
}
