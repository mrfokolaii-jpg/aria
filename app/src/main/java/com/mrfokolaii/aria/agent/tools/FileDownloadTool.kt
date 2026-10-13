package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Download a URL into Download/Aria/downloads */
class FileDownloadTool(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) : AgentTool {

    override val name = "file_download"
    override val description = "Download a file from the internet into Download/Aria/downloads"
    override val category = ToolCategory.WEB

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val url = params["url"]?.toString()?.trim()
            ?: return@withContext ToolResult(false, null, "آدرس دانلود مشخص نیست")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult(false, null, "آدرس باید با http شروع شود")
        }

        try {
            val dir = downloadDir()
            dir.mkdirs()
            val name = params["filename"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
            val safe = name.replace(Regex("[^a-zA-Z0-9._\u0600-\u06FF-]"), "_")
            val dest = File(dir, safe)

            val req = Request.Builder().url(url)
                .header("User-Agent", "ARIA-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ToolResult(false, null, "دانلود ناموفق: HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext ToolResult(false, null, "پاسخ خالی")
                body.byteStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            ToolResult(
                true,
                mapOf(
                    "path" to dest.absolutePath,
                    "size" to dest.length(),
                    "message" to "دانلود شد: ${dest.name} (${dest.length() / 1024} KB)"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "download", e)
            ToolResult(false, null, "خطا در دانلود: ${e.message}")
        }
    }

    private fun downloadDir(): File {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(dl, "Aria"), "downloads").also { it.mkdirs() }
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(true, 0, "File download ready")

    override fun getParamsSchema(): JSONObject = JSONObject()
        .put("url", "string")
        .put("filename", "string?")

    companion object {
        private const val TAG = "FileDownloadTool"
    }
}
