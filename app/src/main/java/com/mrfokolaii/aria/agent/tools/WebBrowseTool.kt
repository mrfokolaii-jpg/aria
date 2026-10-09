package com.mrfokolaii.aria.agent.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

class WebBrowseTool(private val client: OkHttpClient) : AgentTool {
    override val name = "web_browse"
    override val description = "Browse and extract content from a web page"
    override val category = ToolCategory.WEB

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val url = params["url"]?.toString()
            ?: return@withContext ToolResult(false, null, "URL parameter is required")

        val extractMode = params["mode"]?.toString() ?: "text"

        try {
            Log.d(TAG, "Browsing: $url")
            val html = fetchPage(url)
            val content = when (extractMode) {
                "text" -> extractText(html)
                "markdown" -> extractMarkdown(html)
                "html" -> html
                else -> extractText(html)
            }

            ToolResult(
                success = true,
                data = mapOf(
                    "url" to url,
                    "content" to content,
                    "mode" to extractMode
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Browse failed", e)
            ToolResult(false, null, "Failed to browse: ${e.message}")
        }
    }

    private fun fetchPage(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:109.0) Gecko/20100101 Firefox/115.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: throw Exception("Empty response")
        }
    }

    private fun extractText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, footer, header, aside").remove()
        return doc.body().text()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractMarkdown(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, footer, header, aside").remove()

        val content = StringBuilder()

        doc.select("h1").forEach { content.append("# ${it.text()}\n\n") }
        doc.select("h2").forEach { content.append("## ${it.text()}\n\n") }
        doc.select("h3").forEach { content.append("### ${it.text()}\n\n") }
        doc.select("p").forEach { content.append("${it.text()}\n\n") }
        doc.select("a").forEach { content.append("[${it.text()}](${it.attr("href")}) ") }

        return content.toString().trim()
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://example.com")
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                HealthStatus(
                    isHealthy = response.isSuccessful,
                    latencyMs = latency,
                    message = if (response.isSuccessful) "OK" else "HTTP ${response.code}"
                )
            }
        } catch (e: Exception) {
            HealthStatus(
                isHealthy = false,
                latencyMs = System.currentTimeMillis() - start,
                message = e.message ?: "Unknown error"
            )
        }
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("url", JSONObject().apply {
            put("type", "string")
            put("required", true)
            put("description", "URL to browse")
        })
        put("mode", JSONObject().apply {
            put("type", "string")
            put("required", false)
            put("default", "text")
            put("enum", listOf("text", "markdown", "html"))
            put("description", "Extraction mode")
        })
    }

    companion object {
        private const val TAG = "WebBrowseTool"
    }
}
