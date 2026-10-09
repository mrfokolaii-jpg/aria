package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhoneTool(private val context: Context) : AgentTool {
    override val name = "phone_call"
    override val description = "Make phone calls"
    override val category = ToolCategory.SYSTEM

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val phoneNumber = params["phone_number"]?.toString()
            ?: return@withContext ToolResult(false, null, "phone_number is required")

        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, mapOf("status" to "calling", "number" to phoneNumber))
        } catch (e: SecurityException) {
            Log.e(TAG, "Phone permission denied", e)
            ToolResult(false, null, "Phone permission required")
        } catch (e: Exception) {
            Log.e(TAG, "Call failed", e)
            ToolResult(false, null, "Call failed: ${e.message}")
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(
            isHealthy = true,
            latencyMs = 0,
            message = "Phone tool ready"
        )
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("phone_number", JSONObject().apply {
            put("type", "string")
            put("required", true)
            put("description", "Phone number to call")
        })
    }

    companion object {
        private const val TAG = "PhoneTool"
    }
}
