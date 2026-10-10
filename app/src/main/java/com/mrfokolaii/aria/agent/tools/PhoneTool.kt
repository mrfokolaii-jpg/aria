package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhoneTool(private val context: Context) : AgentTool {
    override val name = "phone_call"
    override val description = "Make phone calls or open dialer/contacts"
    override val category = ToolCategory.SYSTEM

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString()?.lowercase() ?: "call"
        val phoneNumber = params["phone_number"]?.toString()?.trim()

        try {
            when {
                action == "contacts" || action == "open_contacts" -> {
                    val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolResult(true, mapOf("status" to "contacts_opened"))
                }
                action == "dialer" || action == "open_dialer" || phoneNumber.isNullOrBlank() -> {
                    val intent = if (!phoneNumber.isNullOrBlank()) {
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                    } else {
                        Intent(Intent.ACTION_DIAL)
                    }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                    ToolResult(true, mapOf("status" to "dialer_opened", "number" to (phoneNumber ?: "")))
                }
                else -> {
                    // ACTION_DIAL does not need CALL_PHONE permission (safer)
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolResult(true, mapOf("status" to "dialing", "number" to phoneNumber))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Phone permission denied", e)
            ToolResult(false, null, "مجوز تماس لازم است")
        } catch (e: Exception) {
            Log.e(TAG, "Call failed", e)
            ToolResult(false, null, "خطا: ${e.message}")
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(isHealthy = true, latencyMs = 0, message = "Phone tool ready")
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("phone_number", JSONObject().apply {
            put("type", "string")
            put("required", false)
            put("description", "Phone number (optional — opens dialer if missing)")
        })
        put("action", JSONObject().apply {
            put("type", "string")
            put("required", false)
            put("description", "call | dialer | contacts")
        })
    }

    companion object {
        private const val TAG = "PhoneTool"
    }
}
