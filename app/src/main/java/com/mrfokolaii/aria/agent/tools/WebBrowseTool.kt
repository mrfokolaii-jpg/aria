package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Open a URL in the system browser, or optionally fetch page text.
 * Default action is "open" so voice commands like "سایت گیت‌هاب را باز کن" work offline.
 */
class WebBrowseTool(
    private val context: Context,
    private val client: OkHttpClient
) : AgentTool {
    override val name = "web_browse"
    override val description = "Open a website in the browser or fetch page text"
    override val category = ToolCategory.WEB

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val rawUrl = params["url"]?.toString()?.trim()
            ?: return@withContext ToolResult(false, null, "آدرس سایت مشخص نشده")

        val url = normalizeUrl(rawUrl)
        val action = params["action"]?.toString()?.lowercase() ?: "open"

        when (action) {
            "fetch", "text", "read" -> fetchContent(url, params["mode"]?.toString() ?: "text")
            else -> openInBrowser(url)
        }
    }

    private fun openInBrowser(url: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                success = true,
                data = mapOf(
                    "status" to "opened",
                    "url" to url,
                    "message" to "سایت باز شد: $url"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "openInBrowser failed", e)
            ToolResult(false, null, "نتوانستم مرورگر را باز کنم: ${e.message}")
        }
    }

    private fun fetchContent(url: String, extractMode: String): ToolResult {
        return try {
            val html = fetchPage(url)
            val content = when (extractMode) {
                "markdown" -> extractMarkdown(html)
                "html" -> html
                else -> extractText(html)
            }
            ToolResult(
                success = true,
                data = mapOf(
                    "url" to url,
                    "content" to content.take(4000),
                    "mode" to extractMode,
                    "message" to "محتوای صفحه خوانده شد"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed", e)
            ToolResult(false, null, "خواندن صفحه ممکن نشد: ${e.message}")
        }
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        return "https://$t"
    }

    private fun fetchPage(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: throw Exception("Empty response")
        }
    }

    private fun extractText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, footer, header, aside").remove()
        return doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    }

    private fun extractMarkdown(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, footer, header, aside").remove()
        val content = StringBuilder()
        doc.select("h1").forEach { content.append("# ${it.text()}\n\n") }
        doc.select("h2").forEach { content.append("## ${it.text()}\n\n") }
        doc.select("p").take(20).forEach { content.append("${it.text()}\n\n") }
        return content.toString().trim()
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(true, 0, "Web browse ready")

    override fun getParamsSchema(): JSONObject = JSONObject()
        .put("url", "string")
        .put("action", "open|fetch")
        .put("mode", "text|markdown|html")

    companion object {
        private const val TAG = "WebBrowseTool"
    }
}
