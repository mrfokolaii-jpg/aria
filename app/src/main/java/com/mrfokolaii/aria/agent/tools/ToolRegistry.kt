package com.mrfokolaii.aria.agent.tools

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()
    private val healthCache = mutableMapOf<String, HealthStatus>()
    private val HEALTH_CACHE_TTL = 300_000L

    fun register(tool: AgentTool?) {
        if (tool == null) {
            Log.w("ToolRegistry", "Skipped null tool")
            return
        }
        tools[tool.name] = tool
    }

    fun getTool(name: String): AgentTool? = tools[name]

    fun getToolsByCategory(category: ToolCategory): List<AgentTool> =
        tools.values.filter { it.category == category }

    fun getAllTools(): List<AgentTool> = tools.values.toList()

    suspend fun checkHealth(toolName: String, forceRefresh: Boolean = false): HealthStatus? {
        val tool = tools[toolName] ?: return null

        val cached = healthCache[toolName]
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() - cached.lastChecked < HEALTH_CACHE_TTL
        ) {
            return cached
        }

        return try {
            val status = tool.healthCheck()
            healthCache[toolName] = status
            status
        } catch (e: Exception) {
            HealthStatus(
                isHealthy = false,
                latencyMs = -1,
                message = "Health check failed: ${e.message}"
            )
        }
    }

    suspend fun checkAllHealth(): Map<String, HealthStatus> = coroutineScope {
        tools.keys.map { name ->
            async { name to checkHealth(name, forceRefresh = true) }
        }.awaitAll().toMap().filterValues { it != null } as Map<String, HealthStatus>
    }
}
