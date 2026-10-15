package com.mrfokolaii.aria.agent.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class WebSearchTool(private val client: OkHttpClient) : AgentTool {
    override val name = "web_search"
    override val description = "Search the web and return results"
    override val category = ToolCategory.WEB

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.toString()?.trim()
            ?: return@withContext ToolResult(false, null, "Query parameter is required")
        val maxResults = (params["max_results"] as? Int) ?: 8

        try {
            val results = searchDuckDuckGo(query, maxResults)
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, mapOf("engine" to "DuckDuckGo", "count" to results.size))
            }
        } catch (e: Exception) {
            Log.w(TAG, "DuckDuckGo HTML failed: ${e.message}")
        }

        try {
            val results = searchDuckDuckGoApi(query, maxResults)
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, mapOf("engine" to "DuckDuckGo-API", "count" to results.size))
            }
        } catch (e: Exception) {
            Log.w(TAG, "DuckDuckGo API failed: ${e.message}")
        }

        try {
            val results = searchWikipedia(query, maxResults)
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, mapOf("engine" to "Wikipedia", "count" to results.size))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia failed: ${e.message}")
        }

        ToolResult(false, null, "جستجوی اینترنتی الان در دسترس نیست. اتصال را چک کن.")
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
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
        val resultRegex = Regex("""class=[\"']result__a[\"']\\s+href=[\"']([^\"']+)[\"'][^>]*>([^<]+)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class=[\"']result__snippet[\"'][^>]*>([^<]+)</""", RegexOption.DOT_MATCHES_ALL)
        val matches = resultRegex.findAll(html).take(maxResults)
        val snippets = snippetRegex.findAll(html).take(maxResults).map { it.groupValues[1].trim() }.toList()
        matches.forEachIndexed { index, match ->
            val url = match.groupValues[1].replace("&", "&")
            val title = match.groupValues[2].trim()
            val snippet = snippets.getOrNull(index) ?: ""
            if (url.startsWith("http")) results.add(SearchResult(title, url, snippet))
        }
        return results
    }

    private fun searchDuckDuckGoApi(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val request = Request.Builder().url(url).header("User-Agent", "Aria-Android-Assistant").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val out = mutableListOf<SearchResult>()
            val abstractText = json.optString("AbstractText", "")
            val abstractUrl = json.optString("AbstractURL", "")
            val heading = json.optString("Heading", query)
            if (abstractText.isNotBlank()) {
                out.add(SearchResult(heading.ifBlank { query }, abstractUrl.ifBlank { "https://duckduckgo.com/?q=$encoded" }, abstractText.take(300)))
            }
            val related = json.optJSONArray("RelatedTopics")
            if (related != null) {
                for (i in 0 until minOf(related.length(), maxResults)) {
                    val item = related.optJSONObject(i) ?: continue
                    val text = item.optString("Text", "")
                    val firstUrl = item.optString("FirstURL", "")
                    if (text.isNotBlank()) out.add(SearchResult(text.take(80), firstUrl, text.take(200)))
                    if (out.size >= maxResults) break
                }
            }
            return out.distinctBy { it.url.ifBlank { it.title } }.take(maxResults)
        }
    }

    private fun searchWikipedia(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://fa.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$maxResults&namespace=0&format=json"
        val request = Request.Builder().url(url).header("User-Agent", "Aria-Android-Assistant").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            if (arr.length() < 4) return emptyList()
            val titles = arr.getJSONArray(1)
            val descs = arr.getJSONArray(2)
            val links = arr.getJSONArray(3)
            val out = mutableListOf<SearchResult>()
            for (i in 0 until minOf(titles.length(), maxResults)) {
                out.add(SearchResult(titles.getString(i), links.optString(i, ""), descs.optString(i, "")))
            }
            return out
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("https://duckduckgo.com").head().build()
            client.newCall(request).execute().use { response ->
                HealthStatus(response.isSuccessful, System.currentTimeMillis() - start, if (response.isSuccessful) "OK" else "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            HealthStatus(false, System.currentTimeMillis() - start, e.message ?: "Unknown error")
        }
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("query", JSONObject().apply { put("type", "string"); put("required", true) })
        put("max_results", JSONObject().apply { put("type", "integer"); put("default", 8) })
    }

    data class SearchResult(val title: String, val url: String, val snippet: String)

    companion object {
        private const val TAG = "WebSearchTool"
    }
}
