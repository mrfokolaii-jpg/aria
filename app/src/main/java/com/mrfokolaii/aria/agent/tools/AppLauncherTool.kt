package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                .sortedBy { (it["name"] as String).lowercase() }
            ToolResult(true, apps, metadata = mapOf("count" to apps.size))
        } catch (e: Exception) {
            ToolResult(false, null, "Failed to list apps: ${e.message}")
        }
    }

    private fun launchApp(params: Map<String, Any>): ToolResult {
        val appName = params["app_name"]?.toString()?.trim()
        val packageName = params["package_name"]?.toString()?.trim()

        if (appName.isNullOrBlank() && packageName.isNullOrBlank()) {
            return ToolResult(false, null, "نام برنامه مشخص نیست")
        }

        return try {
            val pm = context.packageManager
            val target = packageName
                ?: resolveKnownPackage(appName!!)
                ?: findPackageByName(appName!!, pm)

            if (target == null) {
                return ToolResult(false, null, "برنامه «$appName» روی گوشی پیدا نشد")
            }

            val intent = pm.getLaunchIntentForPackage(target)
                ?: return ToolResult(false, null, "نمی‌توان «$appName» را باز کرد")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(
                true,
                mapOf(
                    "status" to "launched",
                    "package" to target,
                    "app" to (appName ?: target),
                    "message" to "برنامه باز شد"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
            ToolResult(false, null, "خطا در باز کردن برنامه: ${e.message}")
        }
    }

    private fun resolveKnownPackage(name: String): String? {
        val key = name.lowercase().replace(" ", "").replace("‌", "")
        val map = mapOf(
            // Iranian messengers
            "روبیکا" to "ir.mservices.market",
            "rubika" to "app.rubika",
            "بله" to "com.sabaidea.bale",
            "bale" to "com.sabaidea.bale",
            "ایتا" to "ir.eitaa.messenger",
            "eitaa" to "ir.eitaa.messenger",
            // Global
            "تلگرام" to "org.telegram.messenger",
            "telegram" to "org.telegram.messenger",
            "واتساپ" to "com.whatsapp",
            "واتساپ" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "اینستاگرام" to "com.instagram.android",
            "اینستا" to "com.instagram.android",
            "instagram" to "com.instagram.android",
            "کروم" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "گوگلکروم" to "com.android.chrome",
            "فایرفاکس" to "org.mozilla.firefox",
            "firefox" to "org.mozilla.firefox",
            "فایر فاکس" to "org.mozilla.firefox",
            // VPN common
            "vpn" to null, // resolved by search
            "وی‌پی‌ان" to null,
            "ویپیان" to null
        )
        // Direct package if installed
        val candidates = when {
            key.contains("روبیکا") || key.contains("rubika") ->
                listOf("app.rubika", "ir.mservices.market", "com.rubika")
            key.contains("بله") || key.contains("bale") ->
                listOf("com.sabaidea.bale", "ir.nasim")
            key.contains("ایتا") || key.contains("eitaa") || key.contains("eita") ->
                listOf("ir.eitaa.messenger", "org.telegram.plus")
            key.contains("تلگرام") || key.contains("telegram") ->
                listOf("org.telegram.messenger", "org.telegram.messenger.web")
            key.contains("واتس") || key.contains("whatsapp") ->
                listOf("com.whatsapp", "com.whatsapp.w4b")
            key.contains("اینستا") || key.contains("instagram") ->
                listOf("com.instagram.android")
            key.contains("کروم") || key.contains("chrome") ->
                listOf("com.android.chrome")
            key.contains("فایرفاکس") || key.contains("firefox") || key.contains("فایر") ->
                listOf("org.mozilla.firefox")
            key.contains("vpn") || key.contains("ویپیان") || key.contains("وی‌پی‌ان") || key.contains("وی پی ان") ->
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
                if (label.contains("vpn") || label.contains("وی‌پی‌ان") || label.contains("proxy") ||
                    pkg.contains("vpn", ignoreCase = true)
                ) {
                    out.add(pkg)
                }
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
        val q = appName.lowercase().replace(" ", "")
        var best: String? = null
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val label = ri.loadLabel(pm).toString()
            val labelNorm = label.lowercase().replace(" ", "")
            val pkg = ri.activityInfo.packageName
            if (labelNorm == q || labelNorm.contains(q) || q.contains(labelNorm)) {
                return pkg
            }
            if (best == null && (labelNorm.startsWith(q.take(3)) || q.startsWith(labelNorm.take(3)))) {
                best = pkg
            }
        }
        return best
    }

    companion object {
        private const val TAG = "AppLauncherTool"
    }
}
