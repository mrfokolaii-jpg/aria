package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SMSTool(private val context: Context) : AgentTool {
    override val name = "sms"
    override val description = "Send SMS messages"
    override val category = ToolCategory.SYSTEM

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val phoneNumber = params["phone_number"]?.toString()
            ?: return@withContext ToolResult(false, null, "phone_number is required")
        val message = params["message"]?.toString()
            ?: return@withContext ToolResult(false, null, "message is required")

        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            ToolResult(true, mapOf("status" to "sent", "to" to phoneNumber))
        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission denied", e)
            ToolResult(false, null, "SMS permission required")
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed", e)
            ToolResult(false, null, "Send failed: ${e.message}")
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(
            isHealthy = true,
            latencyMs = 0,
            message = "SMS tool ready"
        )
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("phone_number", JSONObject().apply {
            put("type", "string")
            put("required", true)
        })
        put("message", JSONObject().apply {
            put("type", "string")
            put("required", true)
        })
    }

    companion object {
        private const val TAG = "SMSTool"
    }
}
