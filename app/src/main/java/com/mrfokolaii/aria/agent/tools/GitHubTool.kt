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
        val query = params["query"]?.toString()
            ?: return@withContext ToolResult(false, null, "Query is required")

        // First check Knowledge Base
        knowledgeBase?.let { kb ->
            val kbResults = kb.search(query)
            if (kbResults.isNotEmpty()) {
                Log.d(TAG, "Found ${kbResults.size} results in Knowledge Base")
            }
        }

        val url = "https://api.github.com/search/repositories?q=${query.replace(" ", "+")}&sort=stars&per_page=10"
        val json = fetchGitHubAPI(url)
        val items = json.getJSONArray("items")

        val results = mutableListOf<Map<String, Any>>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val fullName = item.getString("full_name")
            val inKB = knowledgeBase?.hasProject(fullName) ?: false

            results.add(mapOf(
                "name" to fullName,
                "description" to item.optString("description", ""),
                "url" to item.getString("html_url"),
                "stars" to item.getInt("stargazers_count"),
                "language" to item.optString("language", "Unknown"),
                "in_knowledge_base" to inKB
            ))
        }

        ToolResult(true, mapOf(
            "results" to results,
            "message" to "Found ${results.size} repositories. Use 'save_to_kb' to document one."
        ))
    }

    private fun getFile(params: Map<String, Any>): ToolResult {
        val repo = params["repo"]?.toString()
            ?: return ToolResult(false, null, "Repo is required")
        val path = params["path"]?.toString()
            ?: return ToolResult(false, null, "Path is required")
        val branch = params["branch"]?.toString() ?: "main"

        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$branch"
        val json = fetchGitHubAPI(url)

        val content = json.optString("content", "")
        val decoded = if (content.isNotEmpty()) {
            String(android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT))
        } else {
            ""
        }

        return ToolResult(true, mapOf(
            "path" to path,
            "content" to decoded,
            "size" to json.optInt("size", 0)
        ))
    }

    private fun listIssues(params: Map<String, Any>): ToolResult {
        val repo = params["repo"]?.toString()
            ?: return ToolResult(false, null, "Repo is required")
        val state = params["state"]?.toString() ?: "open"

        val url = "https://api.github.com/repos/$repo/issues?state=$state&per_page=20"
        val array = fetchGitHubAPIArray(url)

        val issues = mutableListOf<Map<String, Any>>()
        for (i in 0 until array.length()) {
            val issue = array.getJSONObject(i)
            issues.add(mapOf(
                "number" to issue.getInt("number"),
                "title" to issue.getString("title"),
                "state" to issue.getString("state"),
                "url" to issue.getString("html_url"),
                "created_at" to issue.getString("created_at")
            ))
        }

        return ToolResult(true, issues)
    }

    private fun getReadme(params: Map<String, Any>): ToolResult {
        val repo = params["repo"]?.toString()
            ?: return ToolResult(false, null, "Repo is required")

        val url = "https://api.github.com/repos/$repo/readme"
        val json = fetchGitHubAPI(url)

        val content = json.optString("content", "")
        val decoded = if (content.isNotEmpty()) {
            String(android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT))
        } else {
            ""
        }

        return ToolResult(true, mapOf(
            "content" to decoded,
            "name" to json.optString("name", "README.md")
        ))
    }

    private fun fetchGitHubAPI(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ARIA-Agent")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response")
            return JSONObject(body)
        }
    }

    private fun fetchGitHubAPIArray(url: String): JSONArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ARIA-Agent")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response")
            return JSONArray(body)
        }
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://api.github.com/zen")
                .header("User-Agent", "ARIA-Agent")
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

    private suspend fun saveToKnowledgeBase(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val kb = knowledgeBase
            ?: return@withContext ToolResult(false, null, "Knowledge Base not available")

        val repo = params["repo"]?.toString()
            ?: return@withContext ToolResult(false, null, "Repo parameter required")

        try {
            // Fetch README for description
            val readme = try {
                val readmeResult = getReadme(mapOf("repo" to repo))
                if (readmeResult.success) {
                    (readmeResult.data as? Map<*, *>)?.get("content")?.toString()?.take(2000) ?: ""
                } else ""
            } catch (e: Exception) {
                ""
            }

            // Fetch repo info
            val url = "https://api.github.com/repos/$repo"
            val json = fetchGitHubAPI(url)

            val knowledge = ProjectKnowledge(
                repoFullName = repo,
                url = json.getString("html_url"),
                purpose = params["purpose"]?.toString() ?: "General use",
                description = readme.ifEmpty { json.optString("description", "") },
                language = json.optString("language", "Unknown"),
                lastReviewed = KnowledgeBase.getCurrentTimestamp(),
                technologies = listOf(json.optString("language", "Unknown")),
                notes = params["notes"]?.toString() ?: ""
            )

            kb.saveProject(knowledge)

            ToolResult(true, mapOf(
                "repo" to repo,
                "message" to "Saved $repo to Knowledge Base"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to KB", e)
            ToolResult(false, null, "Failed to save: ${e.message}")
        }
    }

    private suspend fun searchKnowledgeBase(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val kb = knowledgeBase
            ?: return@withContext ToolResult(false, null, "Knowledge Base not available")

        val query = params["query"]?.toString()
            ?: return@withContext ToolResult(false, null, "Query parameter required")

        val results = kb.search(query)

        ToolResult(true, mapOf(
            "results" to results.map { mapOf(
                "repo" to it.repoFullName,
                "url" to it.url,
                "purpose" to it.purpose,
                "language" to it.language,
                "last_reviewed" to it.lastReviewed
            ) },
            "count" to results.size
        ))
    }

    private suspend fun checkKnowledgeBase(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val kb = knowledgeBase
            ?: return@withContext ToolResult(false, null, "Knowledge Base not available")

        val repo = params["repo"]?.toString()

        if (repo != null) {
            val exists = kb.hasProject(repo)
            val project = if (exists) kb.getProject(repo) else null

            return@withContext ToolResult(true, mapOf(
                "repo" to repo,
                "exists" to exists,
                "project" to project
            ))
        } else {
            val allProjects = kb.listAll()
            return@withContext ToolResult(true, mapOf(
                "projects" to allProjects,
                "count" to allProjects.size
            ))
        }
    }

    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("action", JSONObject().apply {
            put("type", "string")
            put("required", true)
            put("enum", listOf("search", "get_file", "list_issues", "get_readme", "save_to_kb", "search_kb", "check_kb"))
        })
        put("query", JSONObject().apply {
            put("type", "string")
            put("description", "Search query")
        })
        put("repo", JSONObject().apply {
            put("type", "string")
            put("description", "Repository full name (owner/repo)")
        })
        put("path", JSONObject().apply {
            put("type", "string")
            put("description", "File path")
        })
        put("purpose", JSONObject().apply {
            put("type", "string")
            put("description", "Project purpose (for save_to_kb)")
        })
        put("notes", JSONObject().apply {
            put("type", "string")
            put("description", "Additional notes (for save_to_kb)")
        })
    }

    companion object {
        private const val TAG = "GitHubTool"
    }
}
