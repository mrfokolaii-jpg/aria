package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SMSTool(private val context: Context) : AgentTool {
    override val name = "sms"
    override val description = "Open SMS app and compose message to a number or contact name"
    override val category = ToolCategory.SYSTEM

    private val phoneTool = PhoneTool(context)

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val phoneNumber = params["phone_number"]?.toString()?.trim()
        val contactName = params["contact_name"]?.toString()?.trim()
        val message = params["message"]?.toString()?.trim() ?: params["body"]?.toString()?.trim()

        try {
            var target = phoneNumber
            var resolvedName: String? = null

            if (target.isNullOrBlank() && !contactName.isNullOrBlank()) {
                val matches = phoneTool.findContacts(contactName)
                if (matches.isEmpty()) {
                    openSmsApp()
                    return@withContext ToolResult(
                        true,
                        mapOf(
                            "status" to "not_found",
                            "message" to "مخاطب «$contactName» پیدا نشد. برنامه پیامک باز شد."
                        )
                    )
                }
                target = matches.first().second
                resolvedName = matches.first().first
            }

            if (target.isNullOrBlank()) {
                openSmsApp()
                return@withContext ToolResult(
                    true,
                    mapOf("status" to "sms_opened", "message" to "برنامه پیامک باز شد")
                )
            }

            val uri = Uri.parse("smsto:$target")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (!message.isNullOrBlank()) {
                    putExtra("sms_body", message)
                    putExtra(Intent.EXTRA_TEXT, message)
                }
            }
            context.startActivity(intent)

            val who = resolvedName ?: target
            val bodyHint = if (!message.isNullOrBlank()) " با متن آماده" else ""
            ToolResult(
                true,
                mapOf(
                    "status" to "compose",
                    "to" to who,
                    "number" to target,
                    "message" to "پیامک به $who$bodyHint باز شد"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
            ToolResult(false, null, "خطا در پیامک: ${e.message}")
        }
    }

    private fun openSmsApp() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MESSAGING)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "SMSTool"
    }
}
