package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AppLauncherTool(private val context: Context) : AgentTool {
    override val name = "app_launcher"
    override val description = "Launch installed apps"
    override val category = ToolCategory.SYSTEM

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString() ?: "launch"

        when (action) {
            "list" -> listApps()
            "launch" -> launchApp(params)
            else -> ToolResult(false, null, "Unknown action: $action")
        }
    }

    private fun listApps(): ToolResult {
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(intent, 0)
                .map { resolveInfo ->
                    mapOf(
                        "name" to resolveInfo.loadLabel(pm).toString(),
                        "package" to resolveInfo.activityInfo.packageName
                    )
                }
                .take(100)

            return ToolResult(true, apps, metadata = mapOf("count" to apps.size))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list apps", e)
            return ToolResult(false, null, "Failed to list apps: ${e.message}")
        }
    }

    private fun launchApp(params: Map<String, Any>): ToolResult {
        val appName = params["app_name"]?.toString()
        val packageName = params["package_name"]?.toString()

        if (appName == null && packageName == null) {
            return ToolResult(false, null, "app_name or package_name required")
        }

        try {
            val pm = context.packageManager
            val targetPackage = packageName ?: findPackageByName(appName!!, pm)

            if (targetPackage == null) {
                return ToolResult(false, null, "App not found: $appName")
            }

            val intent = pm.getLaunchIntentForPackage(targetPackage)
            if (intent == null) {
                return ToolResult(false, null, "Cannot launch app: $targetPackage")
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            return ToolResult(true, mapOf("status" to "launched", "package" to targetPackage))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app", e)
            return ToolResult(false, null, "Launch failed: ${e.message}")
        }
    }

    private fun findPackageByName(name: String, pm: PackageManager): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?.activityInfo?.packageName
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(
            isHealthy = true,
            latencyMs = 0,
            message = "App launcher ready"
        )
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("action", JSONObject().apply {
            put("type", "string")
            put("enum", listOf("list", "launch"))
        })
        put("app_name", JSONObject().apply {
            put("type", "string")
            put("description", "App name to search")
        })
        put("package_name", JSONObject().apply {
            put("type", "string")
            put("description", "Exact package name")
        })
    }

    companion object {
        private const val TAG = "AppLauncherTool"
    }
}
