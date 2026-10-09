package com.mrfokolaii.aria.agent

import android.util.Log
import com.mrfokolaii.aria.agent.tools.*
import android.accessibilityservice.AccessibilityService
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

    init {
        registerAllTools()
    }

    private val enhancedAccessibility = EnhancedAccessibilityTool()

    fun setAccessibilityService(service: android.accessibilityservice.AccessibilityService?) {
        enhancedAccessibility.setService(service)
    }

    private fun registerAllTools() {
        // Web Tools
        registry.register(WebSearchTool(client))
        registry.register(WebBrowseTool(client))

        // Code Repository Tools (with Knowledge Base)
        registry.register(GitHubTool(client, context))

        // Media Tools
        registry.register(YouTubeTool(client))

        // Social Media Tools (limited to functional ones)
        registry.register(RedditTool(client))
        registry.register(InstagramTool(client))
        registry.register(BilibiliTool(client))
        // Twitter, Facebook, LinkedIn, Xiaohongshu disabled - require API auth

        // RSS Tools
        registry.register(RSSReaderTool(client))

        // System Tools (require Context)
        context?.let { ctx ->
            registry.register(PhoneTool(ctx))
            registry.register(SMSTool(ctx))
            registry.register(AppLauncherTool(ctx))
            registry.register(ScreenReaderTool())
            registry.register(enhancedAccessibility)

            // Iranian Communication Apps
            registry.register(BaleTool(ctx))
            registry.register(EitaaTool(ctx))
            registry.register(RubikaTool(ctx))
            registry.register(WhatsAppTool(ctx))
            registry.register(TelegramTool(ctx))
            registry.register(InstagramAppTool(ctx))
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

    suspend fun checkToolHealth(toolName: String): HealthStatus? {
        return registry.checkHealth(toolName)
    }

    suspend fun checkAllToolsHealth(): Map<String, HealthStatus> {
        return registry.checkAllHealth()
    }

    fun listAllTools(): List<Map<String, Any>> {
        return registry.getAllTools().map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "category" to tool.category.name
            )
        }
    }

    suspend fun runDoctorCheck(): DoctorReport = withContext(Dispatchers.IO) {
        Log.d(TAG, "Running doctor check on all tools...")
        val healthResults = checkAllToolsHealth()

        val healthy = healthResults.filter { it.value.isHealthy }
        val unhealthy = healthResults.filter { !it.value.isHealthy }

        val report = DoctorReport(
            totalTools = registry.getAllTools().size,
            healthyTools = healthy.size,
            unhealthyTools = unhealthy.size,
            healthDetails = healthResults,
            recommendations = generateRecommendations(unhealthy)
        )

        Log.d(TAG, "Doctor check complete: ${healthy.size}/${registry.getAllTools().size} tools healthy")
        return@withContext report
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
