package com.mrfokolaii.aria.agent.tools

import org.json.JSONObject

interface AgentTool {
    val name: String
    val description: String
    val category: ToolCategory

    suspend fun execute(params: Map<String, Any>): ToolResult

    suspend fun healthCheck(): HealthStatus

    fun getParamsSchema(): JSONObject
}

enum class ToolCategory {
    WEB,
    SOCIAL_MEDIA,
    CODE_REPOSITORY,
    MEDIA,
    RSS,
    SYSTEM
}

data class ToolResult(
    val success: Boolean,
    val data: Any?,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

data class HealthStatus(
    val isHealthy: Boolean,
    val latencyMs: Long,
    val message: String,
    val lastChecked: Long = System.currentTimeMillis()
)
