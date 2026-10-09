package com.mrfokolaii.aria.agent.tools

import android.util.Log
import com.mrfokolaii.aria.AriaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ScreenReaderTool : AgentTool {
    override val name = "screen_reader"
    override val description = "Read current screen content via Accessibility"
    override val category = ToolCategory.SYSTEM

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val content = AriaAccessibilityService.lastPageText

            if (content.isEmpty()) {
                return@withContext ToolResult(
                    success = false,
                    data = null,
                    error = "No screen content available. Accessibility service may not be enabled."
                )
            }

            ToolResult(
                success = true,
                data = mapOf(
                    "content" to content,
                    "length" to content.length
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Screen read failed", e)
            ToolResult(false, null, "Failed to read screen: ${e.message}")
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val hasContent = AriaAccessibilityService.lastPageText.isNotEmpty()
        HealthStatus(
            isHealthy = hasContent,
            latencyMs = 0,
            message = if (hasContent) "Screen reader active" else "Accessibility service inactive"
        )
    }

    override fun getParamsSchema(): JSONObject = JSONObject()

    companion object {
        private const val TAG = "ScreenReaderTool"
    }
}
