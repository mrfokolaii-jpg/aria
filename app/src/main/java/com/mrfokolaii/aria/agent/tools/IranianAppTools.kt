package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Tools for Iranian communication apps: Bale, Eitaa, Rubika
 * Uses Android Intent and Accessibility for interaction
 */

abstract class IranianAppTool(protected val context: Context) : AgentTool {
    override val category = ToolCategory.SOCIAL_MEDIA

    protected abstract val packageName: String
    protected abstract val appName: String

    protected fun isAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    protected fun launchApp(): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $appName", e)
            false
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString() ?: "check"

        when (action) {
            "check" -> checkStatus()
            "open" -> openApp()
            "send" -> sendMessage(params)
            else -> ToolResult(false, null, "Unknown action: $action")
        }
    }

    private fun checkStatus(): ToolResult {
        val installed = isAppInstalled()
        return ToolResult(
            success = true,
            data = mapOf(
                "app" to appName,
                "package" to packageName,
                "installed" to installed,
                "message" to if (installed) "$appName is installed" else "$appName is not installed"
            )
        )
    }

    private fun openApp(): ToolResult {
        if (!isAppInstalled()) {
            return ToolResult(
                success = false,
                data = null,
                error = "$appName is not installed"
            )
        }

        val launched = launchApp()
        return ToolResult(
            success = launched,
            data = mapOf(
                "app" to appName,
                "launched" to launched
            ),
            error = if (!launched) "Failed to launch $appName" else null
        )
    }

    private fun sendMessage(params: Map<String, Any>): ToolResult {
        if (!isAppInstalled()) {
            return ToolResult(
                success = false,
                data = null,
                error = "$appName is not installed"
            )
        }

        val message = params["message"]?.toString()
            ?: return ToolResult(false, null, "Message parameter required")

        val phoneNumber = params["phone"]?.toString()
        val username = params["username"]?.toString()

        return ToolResult(
            success = false,
            data = mapOf(
                "app" to appName,
                "message" to "$appName requires manual interaction via Accessibility Service",
                "instruction" to "Open $appName, select contact, then use accessibility to type message"
            ),
            error = "Direct messaging requires Accessibility Service integration"
        )
    }

    override suspend fun healthCheck(): HealthStatus {
        val installed = isAppInstalled()
        return HealthStatus(
            isHealthy = installed,
            latencyMs = 0,
            message = if (installed) "$appName installed" else "$appName not installed"
        )
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("action", JSONObject().apply {
            put("type", "string")
            put("enum", listOf("check", "open", "send"))
        })
        put("message", JSONObject().apply {
            put("type", "string")
            put("description", "Message to send")
        })
        put("phone", JSONObject().apply {
            put("type", "string")
            put("description", "Phone number")
        })
        put("username", JSONObject().apply {
            put("type", "string")
            put("description", "Username")
        })
    }

    companion object {
        private const val TAG = "IranianAppTool"
    }
}

class BaleTool(context: Context) : IranianAppTool(context) {
    override val name = "bale"
    override val description = "Interact with Bale messenger"
    override val packageName = "ir.nasim"
    override val appName = "Bale"
}

class EitaaTool(context: Context) : IranianAppTool(context) {
    override val name = "eitaa"
    override val description = "Interact with Eitaa messenger"
    override val packageName = "ir.eitaa.messenger"
    override val appName = "Eitaa"
}

class RubikaTool(context: Context) : IranianAppTool(context) {
    override val name = "rubika"
    override val description = "Interact with Rubika messenger"
    override val packageName = "ir.resaneh1.iptv"
    override val appName = "Rubika"
}

class WhatsAppTool(context: Context) : IranianAppTool(context) {
    override val name = "whatsapp"
    override val description = "Interact with WhatsApp"
    override val packageName = "com.whatsapp"
    override val appName = "WhatsApp"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString() ?: "check"

        when (action) {
            "send" -> sendWhatsAppMessage(params)
            else -> super.execute(params)
        }
    }

    private fun sendWhatsAppMessage(params: Map<String, Any>): ToolResult {
        if (!isAppInstalled()) {
            return ToolResult(false, null, "WhatsApp is not installed")
        }

        val message = params["message"]?.toString()
            ?: return ToolResult(false, null, "Message parameter required")

        val phoneNumber = params["phone"]?.toString()

        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            if (phoneNumber != null) {
                val cleanNumber = phoneNumber.replace("[^0-9+]".toRegex(), "")
                intent.data = Uri.parse("https://wa.me/$cleanNumber?text=${Uri.encode(message)}")
            } else {
                intent.data = Uri.parse("https://wa.me/?text=${Uri.encode(message)}")
            }
            intent.setPackage(packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            ToolResult(
                success = true,
                data = mapOf(
                    "app" to appName,
                    "message" to message,
                    "phone" to (phoneNumber ?: "not specified")
                )
            )
        } catch (e: Exception) {
            Log.e("WhatsAppTool", "Failed to send message", e)
            ToolResult(false, null, "Failed to open WhatsApp: ${e.message}")
        }
    }
}

class TelegramTool(context: Context) : IranianAppTool(context) {
    override val name = "telegram"
    override val description = "Interact with Telegram"
    override val packageName = "org.telegram.messenger"
    override val appName = "Telegram"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString() ?: "check"

        when (action) {
            "send" -> sendTelegramMessage(params)
            else -> super.execute(params)
        }
    }

    private fun sendTelegramMessage(params: Map<String, Any>): ToolResult {
        if (!isAppInstalled()) {
            return ToolResult(false, null, "Telegram is not installed")
        }

        val message = params["message"]?.toString()
            ?: return ToolResult(false, null, "Message parameter required")

        val username = params["username"]?.toString()
        val phoneNumber = params["phone"]?.toString()

        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            when {
                username != null -> {
                    intent.data = Uri.parse("https://t.me/$username?text=${Uri.encode(message)}")
                }
                phoneNumber != null -> {
                    val cleanNumber = phoneNumber.replace("[^0-9+]".toRegex(), "")
                    intent.data = Uri.parse("https://t.me/+$cleanNumber?text=${Uri.encode(message)}")
                }
                else -> {
                    intent.data = Uri.parse("https://t.me/share/url?url=&text=${Uri.encode(message)}")
                }
            }
            intent.setPackage(packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            ToolResult(
                success = true,
                data = mapOf(
                    "app" to appName,
                    "message" to message,
                    "target" to (username ?: phoneNumber ?: "share")
                )
            )
        } catch (e: Exception) {
            Log.e("TelegramTool", "Failed to send message", e)
            ToolResult(false, null, "Failed to open Telegram: ${e.message}")
        }
    }
}

class InstagramAppTool(context: Context) : IranianAppTool(context) {
    override val name = "instagram_app"
    override val description = "Open and interact with Instagram app"
    override val packageName = "com.instagram.android"
    override val appName = "Instagram"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString() ?: "check"

        when (action) {
            "open_profile" -> openProfile(params)
            "open_post" -> openPost(params)
            else -> super.execute(params)
        }
    }

    private fun openProfile(params: Map<String, Any>): ToolResult {
        if (!isAppInstalled()) {
            return ToolResult(false, null, "Instagram is not installed")
        }

        val username = params["username"]?.toString()
            ?: return ToolResult(false, null, "Username parameter required")

        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://www.instagram.com/$username")
            intent.setPackage(packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            ToolResult(
                success = true,
                data = mapOf(
                    "app" to appName,
                    "action" to "open_profile",
                    "username" to username
                )
            )
        } catch (e: Exception) {
            Log.e("InstagramAppTool", "Failed to open profile", e)
            ToolResult(false, null, "Failed to open Instagram: ${e.message}")
        }
    }

    private fun openPost(params: Map<String, Any>): ToolResult {
        if (!isAppInstalled()) {
            return ToolResult(false, null, "Instagram is not installed")
        }

        val postUrl = params["url"]?.toString()
            ?: return ToolResult(false, null, "URL parameter required")

        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(postUrl)
            intent.setPackage(packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            ToolResult(
                success = true,
                data = mapOf(
                    "app" to appName,
                    "action" to "open_post",
                    "url" to postUrl
                )
            )
        } catch (e: Exception) {
            Log.e("InstagramAppTool", "Failed to open post", e)
            ToolResult(false, null, "Failed to open Instagram: ${e.message}")
        }
    }
}
