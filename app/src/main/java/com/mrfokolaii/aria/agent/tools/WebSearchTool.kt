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
        val maxResults = (params["max_results"] as? Int) ?: 6
        val errors = mutableListOf<String>()

        try {
            val results = searchWikipedia(query, maxResults, "fa")
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, null, mapOf("engine" to "Wikipedia-FA", "count" to results.size))
            }
        } catch (e: Exception) {
            errors.add("wiki-fa: ${e.message}")
        }

        try {
            val results = searchWikipedia(query, maxResults, "en")
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, null, mapOf("engine" to "Wikipedia-EN", "count" to results.size))
            }
        } catch (e: Exception) {
            errors.add("wiki-en: ${e.message}")
        }

        try {
            val results = searchGoogle(query, maxResults)
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, null, mapOf("engine" to "Google", "count" to results.size))
            }
        } catch (e: Exception) {
            errors.add("google: ${e.message}")
            Log.w(TAG, "Google failed: ${e.message}")
        }

        try {
            val results = searchDuckDuckGoApi(query, maxResults)
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, null, mapOf("engine" to "DuckDuckGo-API", "count" to results.size))
            }
        } catch (e: Exception) {
            errors.add("ddg-api: ${e.message}")
        }

        try {
            val results = searchBingRss(query, maxResults)
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, null, mapOf("engine" to "Bing", "count" to results.size))
            }
        } catch (e: Exception) {
            errors.add("bing: ${e.message}")
        }

        try {
            val results = searchDuckDuckGoHtml(query, maxResults)
            if (results.isNotEmpty()) {
                return@withContext ToolResult(true, results, null, mapOf("engine" to "DuckDuckGo-HTML", "count" to results.size))
            }
        } catch (e: Exception) {
            errors.add("ddg-html: ${e.message}")
        }

        ToolResult(false, null, "جستجو ناموفق بود (${errors.take(2).joinToString("; ")}). اتصال یا VPN را بررسی کن.")
    }

    private fun toMap(title: String, url: String, snippet: String): Map<String, String> =
        mapOf(
            "title" to clean(title),
            "url" to clean(url),
            "snippet" to clean(snippet)
        )

    private fun clean(s: String): String =
        s.replace("&", "&")
            .replace("&#39;", "'")
            .replace(""", "\"")
            .replace("&nbsp;", " ")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    private fun searchWikipedia(query: String, maxResults: Int, lang: String): List<Map<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://$lang.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$maxResults&namespace=0&format=json"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Aria-Android-Assistant/1.0")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            if (arr.length() < 4) return emptyList()
            val titles = arr.getJSONArray(1)
            val descs = arr.getJSONArray(2)
            val links = arr.getJSONArray(3)
            val out = mutableListOf<Map<String, String>>()
            for (i in 0 until minOf(titles.length(), maxResults)) {
                out.add(toMap(titles.getString(i), links.optString(i, ""), descs.optString(i, "")))
            }
            return out
        }
    }

    private fun searchGoogle(query: String, maxResults: Int): List<Map<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.google.com/search?q=$encoded&hl=fa&num=$maxResults"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val html = response.body?.string() ?: return emptyList()
            val out = mutableListOf<Map<String, String>>()
            val alt = Regex("href=\"/(?:url\\?q=)?(https?://[^\"&]+)[^\"]*\"[^>]*>([^<]{10,120})</a>", RegexOption.IGNORE_CASE)
            for (m in alt.findAll(html)) {
                val link = m.groupValues[1]
                val title = clean(m.groupValues[2])
                if (link.contains("google.") || title.length < 8) continue
                out.add(toMap(title, link, ""))
                if (out.size >= maxResults) break
            }
            return out.distinctBy { it["url"] }.take(maxResults)
        }
    }

    private fun searchDuckDuckGoApi(query: String, maxResults: Int): List<Map<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val request = Request.Builder().url(url).header("User-Agent", "Aria-Android-Assistant/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val out = mutableListOf<Map<String, String>>()
            val abstractText = json.optString("AbstractText", "")
            val abstractUrl = json.optString("AbstractURL", "")
            val heading = json.optString("Heading", query)
            if (abstractText.isNotBlank()) {
                out.add(toMap(heading.ifBlank { query }, abstractUrl.ifBlank { "https://duckduckgo.com/?q=$encoded" }, abstractText.take(300)))
            }
            val related = json.optJSONArray("RelatedTopics")
            if (related != null) {
                for (i in 0 until minOf(related.length(), maxResults)) {
                    val item = related.optJSONObject(i) ?: continue
                    val text = item.optString("Text", "")
                    val firstUrl = item.optString("FirstURL", "")
                    if (text.isNotBlank()) out.add(toMap(text.take(80), firstUrl, text.take(200)))
                    if (out.size >= maxResults) break
                }
            }
            return out.distinctBy { it["url"].orEmpty().ifBlank { it["title"].orEmpty() } }.take(maxResults)
        }
    }

    private fun searchBingRss(query: String, maxResults: Int): List<Map<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/search?q=$encoded&format=rss"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val xml = response.body?.string() ?: return emptyList()
            val out = mutableListOf<Map<String, String>>()
            val itemRegex = Regex("<item>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val titleRegex = Regex("<title><!\\[CDATA\\[(.*?)]]></title>|<title>(.*?)</title>", RegexOption.IGNORE_CASE)
            val linkRegex = Regex("<link>(.*?)</link>", RegexOption.IGNORE_CASE)
            val descRegex = Regex("<description><!\\[CDATA\\[(.*?)]]></description>|<description>(.*?)</description>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (match in itemRegex.findAll(xml).take(maxResults)) {
                val block = match.groupValues[1]
                val title = titleRegex.find(block)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }?.trim().orEmpty()
                val link = linkRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                val desc = descRegex.find(block)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
                if (title.isNotBlank() || link.isNotBlank()) out.add(toMap(title, link, desc.take(200)))
            }
            return out
        }
    }

    private fun searchDuckDuckGoHtml(query: String, maxResults: Int): List<Map<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val html = response.body?.string() ?: throw Exception("Empty response")
            val results = mutableListOf<Map<String, String>>()
            val resultRegex = Regex("""class=[\"']result__a[\"']\\s+href=[\"']([^\"']+)[\"'][^>]*>([^<]+)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("""class=[\"']result__snippet[\"'][^>]*>([^<]+)</""", RegexOption.DOT_MATCHES_ALL)
            val matches = resultRegex.findAll(html).take(maxResults)
            val snippets = snippetRegex.findAll(html).take(maxResults).map { it.groupValues[1].trim() }.toList()
            matches.forEachIndexed { index, match ->
                val link = match.groupValues[1].replace("&", "&")
                val title = match.groupValues[2].trim()
                val snippet = snippets.getOrNull(index) ?: ""
                if (link.startsWith("http")) results.add(toMap(title, link, snippet))
            }
            return results
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("https://fa.wikipedia.org").head()
                .header("User-Agent", "Aria-Android-Assistant/1.0").build()
            client.newCall(request).execute().use { response ->
                HealthStatus(response.isSuccessful, System.currentTimeMillis() - start, if (response.isSuccessful) "OK" else "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            HealthStatus(false, System.currentTimeMillis() - start, e.message ?: "Unknown error")
        }
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("query", JSONObject().apply { put("type", "string"); put("required", true) })
        put("max_results", JSONObject().apply { put("type", "integer"); put("default", 6) })
    }

    companion object {
        private const val TAG = "WebSearchTool"
    }
}
