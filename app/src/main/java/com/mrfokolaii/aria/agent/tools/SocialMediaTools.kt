package com.mrfokolaii.aria.agent.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

abstract class SocialMediaTool(protected val client: OkHttpClient) : AgentTool {
    override val category = ToolCategory.SOCIAL_MEDIA

    protected fun fetchPage(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:109.0) Gecko/20100101 Firefox/115.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: throw Exception("Empty response")
        }
    }
}

class RedditTool(client: OkHttpClient) : SocialMediaTool(client) {
    override val name = "reddit_search"
    override val description = "Search Reddit posts and comments"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.toString()
            ?: return@withContext ToolResult(false, null, "Query is required")

        val subreddit = params["subreddit"]?.toString()
        val sort = params["sort"]?.toString() ?: "relevance"

        try {
            val url = if (subreddit != null) {
                "https://www.reddit.com/r/$subreddit/search.json?q=$query&sort=$sort&restrict_sr=on&limit=20"
            } else {
                "https://www.reddit.com/search.json?q=$query&sort=$sort&limit=20"
            }

            val json = JSONObject(fetchPage(url))
            val posts = json.getJSONObject("data").getJSONArray("children")

            val results = mutableListOf<Map<String, Any>>()
            for (i in 0 until posts.length()) {
                val post = posts.getJSONObject(i).getJSONObject("data")
                results.add(mapOf(
                    "title" to post.getString("title"),
                    "subreddit" to post.getString("subreddit"),
                    "url" to "https://reddit.com${post.getString("permalink")}",
                    "score" to post.getInt("score"),
                    "num_comments" to post.getInt("num_comments")
                ))
            }

            ToolResult(true, results)
        } catch (e: Exception) {
            Log.e(TAG, "Reddit search failed", e)
            ToolResult(false, null, "Reddit search failed: ${e.message}")
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("https://www.reddit.com/").head().build()
            client.newCall(request).execute().use { response ->
                HealthStatus(
                    isHealthy = response.isSuccessful,
                    latencyMs = System.currentTimeMillis() - start,
                    message = if (response.isSuccessful) "OK" else "HTTP ${response.code}"
                )
            }
        } catch (e: Exception) {
            HealthStatus(false, System.currentTimeMillis() - start, e.message ?: "Unknown error")
        }
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("query", JSONObject().apply {
            put("type", "string")
            put("required", true)
        })
        put("subreddit", JSONObject().apply {
            put("type", "string")
            put("required", false)
        })
    }

    companion object {
        private const val TAG = "RedditTool"
    }
}

class TwitterTool(client: OkHttpClient) : SocialMediaTool(client) {
    override val name = "twitter_search"
    override val description = "Search Twitter/X posts (requires auth for full access)"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        // Note: Twitter API v2 requires authentication
        // This is a placeholder implementation
        ToolResult(
            success = false,
            data = null,
            error = "Twitter search requires API authentication. Use web search for public Twitter content."
        )
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("https://twitter.com").head().build()
            client.newCall(request).execute().use { response ->
                HealthStatus(
                    isHealthy = response.isSuccessful,
                    latencyMs = System.currentTimeMillis() - start,
                    message = "Twitter API requires authentication"
                )
            }
        } catch (e: Exception) {
            HealthStatus(false, System.currentTimeMillis() - start, e.message ?: "Unknown error")
        }
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("query", JSONObject().apply {
            put("type", "string")
            put("required", true)
        })
    }
}

class FacebookTool(client: OkHttpClient) : SocialMediaTool(client) {
    override val name = "facebook_search"
    override val description = "Search Facebook (requires auth)"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        ToolResult(
            success = false,
            data = null,
            error = "Facebook search requires Graph API authentication"
        )
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(false, 0, "Facebook requires authentication")
    }

    override fun getParamsSchema(): JSONObject = JSONObject()
}

class InstagramTool(client: OkHttpClient) : SocialMediaTool(client) {
    override val name = "instagram_search"
    override val description = "Search Instagram (requires auth)"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        ToolResult(
            success = false,
            data = null,
            error = "Instagram search requires Graph API authentication"
        )
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(false, 0, "Instagram requires authentication")
    }

    override fun getParamsSchema(): JSONObject = JSONObject()
}

class LinkedInTool(client: OkHttpClient) : SocialMediaTool(client) {
    override val name = "linkedin_search"
    override val description = "Search LinkedIn (requires auth)"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        ToolResult(
            success = false,
            data = null,
            error = "LinkedIn search requires API authentication"
        )
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(false, 0, "LinkedIn requires authentication")
    }

    override fun getParamsSchema(): JSONObject = JSONObject()
}

class BilibiliTool(client: OkHttpClient) : SocialMediaTool(client) {
    override val name = "bilibili_search"
    override val description = "Search Bilibili videos"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.toString()
            ?: return@withContext ToolResult(false, null, "Query is required")

        try {
            val url = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=${query.replace(" ", "+")}"
            val json = JSONObject(fetchPage(url))

            if (json.getInt("code") != 0) {
                return@withContext ToolResult(false, null, "Bilibili API error")
            }

            val results = mutableListOf<Map<String, Any>>()
            val data = json.optJSONObject("data")?.optJSONArray("result")

            data?.let { arr ->
                for (i in 0 until arr.length().coerceAtMost(10)) {
                    val item = arr.optJSONObject(i)
                    item?.let {
                        results.add(mapOf(
                            "title" to it.optString("title", ""),
                            "url" to "https://www.bilibili.com/video/${it.optString("bvid", "")}"
                        ))
                    }
                }
            }

            ToolResult(true, results)
        } catch (e: Exception) {
            ToolResult(false, null, "Bilibili search failed: ${e.message}")
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("https://www.bilibili.com").head().build()
            client.newCall(request).execute().use { response ->
                HealthStatus(
                    isHealthy = response.isSuccessful,
                    latencyMs = System.currentTimeMillis() - start,
                    message = if (response.isSuccessful) "OK" else "HTTP ${response.code}"
                )
            }
        } catch (e: Exception) {
            HealthStatus(false, System.currentTimeMillis() - start, e.message ?: "Unknown error")
        }
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("query", JSONObject().apply {
            put("type", "string")
            put("required", true)
        })
    }
}

class XiaohongshuTool(client: OkHttpClient) : SocialMediaTool(client) {
    override val name = "xiaohongshu_search"
    override val description = "Search Xiaohongshu (Little Red Book)"

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        ToolResult(
            success = false,
            data = null,
            error = "Xiaohongshu requires app-specific authentication"
        )
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        HealthStatus(false, 0, "Xiaohongshu requires authentication")
    }

    override fun getParamsSchema(): JSONObject = JSONObject()
}
