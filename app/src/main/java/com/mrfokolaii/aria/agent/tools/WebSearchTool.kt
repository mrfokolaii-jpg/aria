package com.mrfokolaii.aria.agent.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSearchTool(private val client: OkHttpClient) : AgentTool {
    override val name = "web_search"
    override val description = "Search the web using DuckDuckGo and return results"
    override val category = ToolCategory.WEB

    private val searchEngines = listOf(
        SearchEngine.DuckDuckGo,
        SearchEngine.Brave
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.toString()
            ?: return@withContext ToolResult(false, null, "Query parameter is required")

        val maxResults = (params["max_results"] as? Int) ?: 10

        for (engine in searchEngines) {
            try {
                Log.d(TAG, "Trying search with ${engine.name}")
                val results = searchWith(engine, query, maxResults)
                if (results.isNotEmpty()) {
                    return@withContext ToolResult(
                        success = true,
                        data = results,
                        metadata = mapOf("engine" to engine.name, "count" to results.size)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "${engine.name} search failed: ${e.message}")
                continue
            }
        }

        ToolResult(false, null, "All search engines failed")
    }

    private fun searchWith(engine: SearchEngine, query: String, maxResults: Int): List<SearchResult> {
        return when (engine) {
            SearchEngine.DuckDuckGo -> searchDuckDuckGo(query, maxResults)
            SearchEngine.Brave -> searchBrave(query, maxResults)
        }
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/?q=${query.replace(" ", "+")}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:109.0) Gecko/20100101 Firefox/115.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

            val html = response.body?.string() ?: throw Exception("Empty response")
            return parseDuckDuckGoResults(html, maxResults)
        }
    }

    private fun parseDuckDuckGoResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val resultRegex = Regex("""class=["']result__a["']\s+href=["']([^"']+)["'][^>]*>([^<]+)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class=["']result__snippet["'][^>]*>([^<]+)</""", RegexOption.DOT_MATCHES_ALL)

        val matches = resultRegex.findAll(html).take(maxResults)
        val snippets = snippetRegex.findAll(html).take(maxResults).map { it.groupValues[1].trim() }.toList()

        matches.forEachIndexed { index, match ->
            val url = match.groupValues[1].replace("&amp;", "&")
            val title = match.groupValues[2].trim()
            val snippet = snippets.getOrNull(index) ?: ""

            if (url.startsWith("http")) {
                results.add(SearchResult(title, url, snippet))
            }
        }

        return results
    }

    private fun searchBrave(query: String, maxResults: Int): List<SearchResult> {
        // Fallback implementation - would need Brave Search API key
        throw NotImplementedError("Brave search requires API key")
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://duckduckgo.com")
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
        put("query", JSONObject().apply {
            put("type", "string")
            put("required", true)
            put("description", "Search query")
        })
        put("max_results", JSONObject().apply {
            put("type", "integer")
            put("required", false)
            put("default", 10)
            put("description", "Maximum number of results")
        })
    }

    private enum class SearchEngine {
        DuckDuckGo,
        Brave
    }

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )

    companion object {
        private const val TAG = "WebSearchTool"
    }
}
