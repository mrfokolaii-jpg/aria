package com.mrfokolaii.aria.agent.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class YouTubeTool(private val client: OkHttpClient) : AgentTool {
    override val name = "youtube_search"
    override val description = "Search YouTube videos and get transcripts"
    override val category = ToolCategory.MEDIA

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString() ?: "search"

        try {
            when (action) {
                "search" -> searchVideos(params)
                "get_transcript" -> getTranscript(params)
                else -> ToolResult(false, null, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube operation failed", e)
            ToolResult(false, null, "YouTube operation failed: ${e.message}")
        }
    }

    private fun searchVideos(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString()
            ?: return ToolResult(false, null, "Query is required")

        val url = "https://www.youtube.com/results?search_query=${query.replace(" ", "+")}"
        val html = fetchPage(url)

        val results = parseSearchResults(html)

        return ToolResult(true, results, metadata = mapOf("count" to results.size))
    }

    private fun parseSearchResults(html: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()

        // Extract from ytInitialData JSON
        val dataMatch = Regex("var ytInitialData = (\\{.*?\\});\\s*</script>").find(html)
        if (dataMatch != null) {
            try {
                val json = JSONObject(dataMatch.groupValues[1])
                val contents = json
                    .optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?.optJSONObject(0)
                    ?.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents")

                contents?.let { arr ->
                    for (i in 0 until arr.length().coerceAtMost(10)) {
                        val item = arr.optJSONObject(i)?.optJSONObject("videoRenderer")
                        if (item != null) {
                            val videoId = item.optString("videoId", "")
                            val title = item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""

                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                results.add(mapOf(
                                    "id" to videoId,
                                    "title" to title,
                                    "url" to "https://www.youtube.com/watch?v=$videoId"
                                ))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse ytInitialData: ${e.message}")
            }
        }

        return results
    }

    private fun getTranscript(params: Map<String, Any>): ToolResult {
        val videoId = params["video_id"]?.toString()
            ?: return ToolResult(false, null, "video_id is required")

        // Note: This is a simplified implementation
        // A real implementation would fetch from YouTube's caption API
        return ToolResult(
            success = false,
            data = null,
            error = "Transcript fetching requires YouTube API key or additional scraping logic"
        )
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

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://www.youtube.com")
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
        put("action", JSONObject().apply {
            put("type", "string")
            put("required", true)
            put("enum", listOf("search", "get_transcript"))
        })
        put("query", JSONObject().apply {
            put("type", "string")
            put("description", "Search query (for search action)")
        })
        put("video_id", JSONObject().apply {
            put("type", "string")
            put("description", "YouTube video ID (for get_transcript action)")
        })
    }

    companion object {
        private const val TAG = "YouTubeTool"
    }
}
