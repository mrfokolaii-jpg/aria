package com.mrfokolaii.aria.agent.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

class RSSReaderTool(private val client: OkHttpClient) : AgentTool {
    override val name = "rss_reader"
    override val description = "Read and parse RSS/Atom feeds"
    override val category = ToolCategory.RSS

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val feedUrl = params["url"]?.toString()
            ?: return@withContext ToolResult(false, null, "Feed URL is required")

        val maxItems = (params["max_items"] as? Int) ?: 20

        try {
            Log.d(TAG, "Fetching RSS feed: $feedUrl")
            val xml = fetchFeed(feedUrl)
            val items = parseFeed(xml, maxItems)

            ToolResult(
                success = true,
                data = mapOf(
                    "feed_url" to feedUrl,
                    "items" to items,
                    "count" to items.size
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "RSS fetch failed", e)
            ToolResult(false, null, "RSS fetch failed: ${e.message}")
        }
    }

    private fun fetchFeed(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ARIA-Agent/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: throw Exception("Empty response")
        }
    }

    private fun parseFeed(xml: String, maxItems: Int): List<Map<String, String>> {
        val items = mutableListOf<Map<String, String>>()

        try {
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())

            // Try RSS 2.0 format
            val rssItems = doc.select("item")
            if (rssItems.isNotEmpty()) {
                rssItems.take(maxItems).forEach { item ->
                    items.add(mapOf(
                        "title" to item.selectFirst("title")?.text().orEmpty(),
                        "link" to item.selectFirst("link")?.text().orEmpty(),
                        "description" to item.selectFirst("description")?.text().orEmpty(),
                        "pubDate" to item.selectFirst("pubDate")?.text().orEmpty()
                    ))
                }
                return items
            }

            // Try Atom format
            val atomEntries = doc.select("entry")
            if (atomEntries.isNotEmpty()) {
                atomEntries.take(maxItems).forEach { entry ->
                    val link = entry.selectFirst("link")
                    items.add(mapOf(
                        "title" to entry.selectFirst("title")?.text().orEmpty(),
                        "link" to (link?.attr("href") ?: ""),
                        "description" to entry.selectFirst("summary")?.text().orEmpty(),
                        "pubDate" to entry.selectFirst("updated")?.text().orEmpty()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Feed parsing failed", e)
        }

        return items
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            // Test with a known reliable RSS feed
            val testFeed = "https://news.ycombinator.com/rss"
            val request = Request.Builder()
                .url(testFeed)
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
            put("description", "RSS/Atom feed URL")
        })
        put("max_items", JSONObject().apply {
            put("type", "integer")
            put("required", false)
            put("default", 20)
            put("description", "Maximum number of items to return")
        })
    }

    companion object {
        private const val TAG = "RSSReaderTool"
    }
}
