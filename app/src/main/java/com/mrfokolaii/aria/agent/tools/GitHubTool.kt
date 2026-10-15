package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.knowledge.KnowledgeBase
import com.mrfokolaii.aria.agent.knowledge.ProjectKnowledge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class GitHubTool(private val client: OkHttpClient, private val context: Context? = null) : AgentTool {
    override val name = "github_search"
    override val description = "Search GitHub repositories with Knowledge Base integration"
    override val category = ToolCategory.CODE_REPOSITORY

    private val knowledgeBase: KnowledgeBase? = context?.let { KnowledgeBase(it) }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString() ?: "search"
        try {
            when (action) {
                "search" -> searchRepositories(params)
                "get_file" -> getFile(params)
                "list_issues" -> listIssues(params)
                "get_readme" -> getReadme(params)
                "save_to_kb" -> saveToKnowledgeBase(params)
                "search_kb" -> searchKnowledgeBase(params)
                "check_kb" -> checkKnowledgeBase(params)
                else -> ToolResult(false, null, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub operation failed", e)
            ToolResult(false, null, "GitHub operation failed: ${e.message}")
        }
    }

    private suspend fun searchRepositories(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.toString()?.trim()
            ?: return@withContext ToolResult(false, null, "Query is required")
        if (query.isBlank()) {
            return@withContext ToolResult(false, null, "عبارت جستجو خالی است")
        }

        knowledgeBase?.let { kb ->
            val kbResults = kb.search(query)
            if (kbResults.isNotEmpty()) {
                Log.d(TAG, "Found ${kbResults.size} results in Knowledge Base")
            }
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=8"
        val json = fetchGitHubAPI(url)
        if (!json.has("items")) {
            val msg = json.optString("message", "پاسخ نامعتبر از گیت‌هاب")
            return@withContext ToolResult(false, null, "GitHub: $msg")
        }
        val items = json.getJSONArray("items")

        val results = mutableListOf<Map<String, Any>>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val fullName = item.getString("full_name")
            val inKB = knowledgeBase?.hasProject(fullName) ?: false
            results.add(
                mapOf(
                    "name" to fullName,
                    "description" to item.optString("description", ""),
                    "url" to item.getString("html_url"),
                    "stars" to item.getInt("stargazers_count"),
                    "language" to item.optString("language", "Unknown"),
                    "in_knowledge_base" to inKB
                )
            )
        }

        ToolResult(
            true,
            mapOf(
                "results" to results,
                "message" to "Found ${results.size} repositories"
            )
        )
    }

    private fun getFile(params: Map<String, Any>): ToolResult {
        val repo = params["repo"]?.toString() ?: return ToolResult(false, null, "Repo is required")
        val path = params["path"]?.toString() ?: return ToolResult(false, null, "Path is required")
        val branch = params["branch"]?.toString() ?: "main"
        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$branch"
        val json = fetchGitHubAPI(url)
        val content = json.optString("content", "")
        val decoded = if (content.isNotEmpty()) {
            String(android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT))
        } else ""
        return ToolResult(true, mapOf("path" to path, "content" to decoded, "size" to json.optInt("size", 0)))
    }

    private fun listIssues(params: Map<String, Any>): ToolResult {
        val repo = params["repo"]?.toString() ?: return ToolResult(false, null, "Repo is required")
        val state = params["state"]?.toString() ?: "open"
        val url = "https://api.github.com/repos/$repo/issues?state=$state&per_page=10"
        val arr = fetchGitHubAPIArray(url)
        val results = mutableListOf<Map<String, Any>>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            results.add(
                mapOf(
                    "number" to item.getInt("number"),
                    "title" to item.getString("title"),
                    "state" to item.getString("state"),
                    "url" to item.getString("html_url")
                )
            )
        }
        return ToolResult(true, mapOf("issues" to results))
    }

    private fun getReadme(params: Map<String, Any>): ToolResult {
        val repo = params["repo"]?.toString() ?: return ToolResult(false, null, "Repo is required")
        val url = "https://api.github.com/repos/$repo/readme"
        val json = fetchGitHubAPI(url)
        val content = json.optString("content", "")
        val decoded = if (content.isNotEmpty()) {
            String(android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT))
        } else ""
        return ToolResult(true, mapOf("content" to decoded.take(8000)))
    }

    private fun fetchGitHubAPI(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Aria-Android-Assistant")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(body).optString("message", body.take(200))
                } catch (_: Exception) {
                    body.take(200)
                }
                throw Exception("HTTP ${response.code}: $detail")
            }
            if (body.isBlank()) throw Exception("Empty response")
            return JSONObject(body)
        }
    }

    private fun fetchGitHubAPIArray(url: String): JSONArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Aria-Android-Assistant")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${body.take(200)}")
            return JSONArray(body)
        }
    }

    private suspend fun saveToKnowledgeBase(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val kb = knowledgeBase ?: return@withContext ToolResult(false, null, "Knowledge Base not available")
        val repo = params["repo"]?.toString() ?: return@withContext ToolResult(false, null, "repo required")
        val purpose = params["purpose"]?.toString() ?: ""
        val notes = params["notes"]?.toString() ?: ""
        val project = ProjectKnowledge(
            repoFullName = repo,
            url = "https://github.com/$repo",
            purpose = purpose,
            language = params["language"]?.toString() ?: "",
            notes = notes,
            lastReviewed = System.currentTimeMillis()
        )
        kb.saveProject(project)
        ToolResult(true, mapOf("saved" to repo))
    }

    private suspend fun searchKnowledgeBase(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val kb = knowledgeBase ?: return@withContext ToolResult(false, null, "Knowledge Base not available")
        val query = params["query"]?.toString() ?: return@withContext ToolResult(false, null, "Query parameter required")
        val results = kb.search(query)
        ToolResult(
            true,
            mapOf(
                "results" to results.map {
                    mapOf(
                        "repo" to it.repoFullName,
                        "url" to it.url,
                        "purpose" to it.purpose,
                        "language" to it.language,
                        "last_reviewed" to it.lastReviewed
                    )
                },
                "count" to results.size
            )
        )
    }

    private suspend fun checkKnowledgeBase(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val kb = knowledgeBase ?: return@withContext ToolResult(false, null, "Knowledge Base not available")
        val repo = params["repo"]?.toString()
        if (repo != null) {
            val exists = kb.hasProject(repo)
            val project = if (exists) kb.getProject(repo) else null
            ToolResult(true, mapOf("repo" to repo, "exists" to exists, "project" to project))
        } else {
            val allProjects = kb.listAll()
            ToolResult(true, mapOf("projects" to allProjects, "count" to allProjects.size))
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://api.github.com/zen")
                .header("User-Agent", "Aria-Android-Assistant")
                .build()
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
        put("action", JSONObject().apply {
            put("type", "string")
            put("required", true)
        })
        put("query", JSONObject().apply { put("type", "string") })
        put("repo", JSONObject().apply { put("type", "string") })
        put("path", JSONObject().apply { put("type", "string") })
    }

    companion object {
        private const val TAG = "GitHubTool"
    }
}
