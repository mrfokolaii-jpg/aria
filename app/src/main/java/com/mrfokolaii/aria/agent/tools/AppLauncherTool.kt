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
    override val description = "Launch installed apps by Persian or English name"
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
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, 0)
                .map { ri ->
                    mapOf(
                        "name" to ri.loadLabel(pm).toString(),
                        "package" to ri.activityInfo.packageName
                    )
                }
                .sortedBy { it["name"]?.toString()?.lowercase() }
            ToolResult(true, mapOf("apps" to apps, "message" to "${apps.size} برنامه پیدا شد"))
        } catch (e: Exception) {
            ToolResult(false, null, e.message)
        }
    }

    private fun launchApp(params: Map<String, Any>): ToolResult {
        val rawName = params["app_name"]?.toString()?.trim()
        val packageName = params["package_name"]?.toString()?.trim()
        val appName = rawName?.let { cleanAppQuery(it) }

        if (appName.isNullOrBlank() && packageName.isNullOrBlank()) {
            return ToolResult(false, null, "نام برنامه مشخص نیست")
        }

        return try {
            val pm = context.packageManager
            val target = packageName
                ?: (appName?.let { resolveKnownPackage(it) })
                ?: (appName?.let { findPackageByName(it, pm) })

            if (target == null) {
                return ToolResult(false, null, "برنامه «${rawName ?: appName}» روی گوشی پیدا نشد. بگو مثلاً تلگرام یا پیامک.")
            }

            val launch = pm.getLaunchIntentForPackage(target)
                ?: return ToolResult(false, null, "نتوانستم برنامه را باز کنم")

            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
            } catch (_: Exception) {
                rawName ?: target
            }
            ToolResult(
                true,
                mapOf(
                    "status" to "launched",
                    "package" to target,
                    "message" to "برنامه باز شد: $label"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
            ToolResult(false, null, "خطا در باز کردن برنامه: ${e.message}")
        }
    }

    private fun cleanAppQuery(name: String): String {
        return name
            .replace(Regex("(باز\\s*کن|اجرا\\s*کن|برنامه|اپلیکیشن|اپ|لطفا|کن|بکن|رو|را|و$)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun resolveKnownPackage(name: String): String? {
        val key = name.lowercase()
            .replace(" ", "")
            .replace("‌", "")
            .replace("ي", "ی")
            .replace("ك", "ک")

        val candidates: List<String> = when {
            key.contains("روبیکا") || key.contains("rubika") ->
                listOf("app.rbmain.a1", "com.rubika")
            key.contains("بله") || key.contains("bale") ->
                listOf("ir.nasim")
            key.contains("ایتا") || key.contains("eitaa") || key.contains("eita") ->
                listOf("ir.eitaa.messenger")
            key.contains("تلگرام") || key.contains("telegram") ->
                listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
            key.contains("واتس") || key.contains("whatsapp") ->
                listOf("com.whatsapp", "com.whatsapp.w4b")
            key.contains("اینستا") || key.contains("instagram") ->
                listOf("com.instagram.android")
            key.contains("کروم") || key.contains("chrome") ->
                listOf("com.android.chrome")
            key.contains("فایرفاکس") || key.contains("firefox") || key.contains("فایر") ->
                listOf("org.mozilla.firefox")
            key.contains("مرورگر") || key.contains("browser") || key.contains("براوزر") ->
                listOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.opera.browser")
            key.contains("پیامک") || key.contains("پیامرسان") || key.contains("پیامرسان") ||
                key.contains("اساماس") || key.contains("sms") || key.contains("message") ||
                key.contains("messaging") || key.contains("messages") ->
                listOf(
                    "com.google.android.apps.messaging",
                    "com.samsung.android.messaging",
                    "com.android.mms",
                    "com.android.messaging",
                    "com.miui.sms"
                )
            key.contains("تماس") || key.contains("شمارهگیر") || key.contains("phone") || key.contains("dialer") ->
                listOf(
                    "com.google.android.dialer",
                    "com.samsung.android.dialer",
                    "com.android.dialer",
                    "com.android.contacts"
                )
            key.contains("گالری") || key.contains("gallery") || key.contains("photos") || key.contains("عکس") ->
                listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery")
            key.contains("دوربین") || key.contains("camera") ->
                listOf("com.android.camera", "com.sec.android.app.camera", "com.android.camera2")
            key.contains("تنظیمات") || key.contains("settings") ->
                listOf("com.android.settings")
            key.contains("یوتیوب") || key.contains("youtube") ->
                listOf("com.google.android.youtube")
            key.contains("نقشه") || key.contains("maps") || key.contains("گوگلمپ") ->
                listOf("com.google.android.apps.maps")
            key.contains("فروشگاه") || key.contains("پلی") || key.contains("playstore") || key.contains("myket") || key.contains("بازار") ->
                listOf("com.android.vending", "com.farsitel.bazaar", "ir.mservices.market")
            key.contains("vpn") || key.contains("ویپیان") || key.contains("ویپان") ->
                findVpnPackage()
            else -> emptyList()
        }
        for (pkg in candidates) {
            if (isInstalled(pkg)) return pkg
        }
        return null
    }

    private fun findVpnPackage(): List<String> {
        val pm = context.packageManager
        val out = mutableListOf<String>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val label = ri.loadLabel(pm).toString().lowercase()
                val pkg = ri.activityInfo.packageName
                if (label.contains("vpn") || label.contains("وی") || label.contains("proxy") ||
                    pkg.contains("vpn", ignoreCase = true)
                ) out.add(pkg)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun findPackageByName(appName: String, pm: PackageManager): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val q = appName.lowercase()
            .replace(" ", "")
            .replace("‌", "")
            .replace("ي", "ی")
            .replace("ك", "ک")
        if (q.length < 2) return null

        var bestPkg: String? = null
        var bestScore = 0
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val label = ri.loadLabel(pm).toString()
            val labelNorm = label.lowercase().replace(" ", "").replace("‌", "")
            val pkg = ri.activityInfo.packageName
            val score = when {
                labelNorm == q -> 100
                labelNorm.contains(q) -> 80
                q.contains(labelNorm) && labelNorm.length >= 3 -> 70
                labelNorm.startsWith(q.take(3)) -> 40
                q.length >= 4 && labelNorm.contains(q.take(4)) -> 35
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestPkg = pkg
            }
        }
        return if (bestScore >= 35) bestPkg else null
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(true, 0, "App launcher ready")

    override fun getParamsSchema(): JSONObject = JSONObject()
        .put("action", "launch|list")
        .put("app_name", "string?")
        .put("package_name", "string?")

    companion object {
        private const val TAG = "AppLauncherTool"
    }
}
