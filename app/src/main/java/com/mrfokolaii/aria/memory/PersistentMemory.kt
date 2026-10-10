package com.mrfokolaii.aria.memory

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent local memory stored under /sdcard/Grok/ (or internal fallback).
 * Structure:
 *   Grok/
 *     notes/           text notes by topic
 *     sites/           website catalog (url, category, features)
 *     conversations/   chat history JSON
 *     github/          project knowledge mirrors
 *     index.json       master index
 */
class PersistentMemory(private val context: Context) {

    private val rootDir: File by lazy {
        val external = File(Environment.getExternalStorageDirectory(), "Grok")
        try {
            if (!external.exists()) external.mkdirs()
            if (external.canWrite()) external
            else File(context.filesDir, "Grok").also { it.mkdirs() }
        } catch (_: Exception) {
            File(context.filesDir, "Grok").also { it.mkdirs() }
        }
    }

    private val notesDir get() = File(rootDir, "notes").also { it.mkdirs() }
    private val sitesDir get() = File(rootDir, "sites").also { it.mkdirs() }
    private val convDir get() = File(rootDir, "conversations").also { it.mkdirs() }
    private val githubDir get() = File(rootDir, "github").also { it.mkdirs() }
    private val indexFile get() = File(rootDir, "index.json")

    // ── Notes ──────────────────────────────────────────────

    fun saveNote(topic: String, content: String, tags: List<String> = emptyList()): File {
        val safe = sanitize(topic)
        val file = File(notesDir, "$safe.md")
        val header = buildString {
            appendLine("---")
            appendLine("topic: $topic")
            appendLine("tags: ${tags.joinToString(", ")}")
            appendLine("updated: ${now()}")
            appendLine("---")
            appendLine()
        }
        val existing = if (file.exists()) file.readText().substringAfter("---\n\n", "") else ""
        file.writeText(header + existing + "\n\n## ${now()}\n\n$content\n")
        updateIndex("note", topic, file.absolutePath)
        Log.d(TAG, "Note saved: $topic")
        return file
    }

    fun listNotes(): List<String> =
        notesDir.listFiles()?.filter { it.extension == "md" }?.map { it.nameWithoutExtension } ?: emptyList()

    fun readNote(topic: String): String? {
        val file = File(notesDir, "${sanitize(topic)}.md")
        return if (file.exists()) file.readText() else null
    }

    // ── Sites catalog ──────────────────────────────────────

    fun saveSite(
        url: String,
        title: String,
        categories: List<String>,
        features: List<String>,
        summary: String = ""
    ) {
        val host = try {
            java.net.URI(url).host ?: sanitize(url)
        } catch (_: Exception) {
            sanitize(url)
        }
        val file = File(sitesDir, "${sanitize(host)}.json")
        val obj = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        obj.put("url", url)
        obj.put("host", host)
        obj.put("title", title)
        obj.put("categories", JSONArray(categories))
        obj.put("features", JSONArray(features))
        obj.put("summary", summary)
        obj.put("updated", now())
        // Merge previous categories/features
        if (file.exists()) {
            try {
                val old = JSONObject(file.readText())
                val cats = mutableSetOf<String>().apply {
                    addAll(categories)
                    old.optJSONArray("categories")?.let { arr ->
                        for (i in 0 until arr.length()) add(arr.getString(i))
                    }
                }
                val feats = mutableSetOf<String>().apply {
                    addAll(features)
                    old.optJSONArray("features")?.let { arr ->
                        for (i in 0 until arr.length()) add(arr.getString(i))
                    }
                }
                obj.put("categories", JSONArray(cats.toList()))
                obj.put("features", JSONArray(feats.toList()))
            } catch (_: Exception) {}
        }
        file.writeText(obj.toString(2))
        updateIndex("site", host, file.absolutePath, categories)
        Log.d(TAG, "Site saved: $host")
    }

    fun listSites(): List<JSONObject> =
        sitesDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull {
            try { JSONObject(it.readText()) } catch (_: Exception) { null }
        } ?: emptyList()

    fun findSitesByCategory(category: String): List<JSONObject> =
        listSites().filter { site ->
            val cats = site.optJSONArray("categories") ?: return@filter false
            (0 until cats.length()).any { cats.getString(it).contains(category, ignoreCase = true) }
        }

    // ── Conversations ──────────────────────────────────────

    fun appendConversation(sessionId: String, role: String, text: String) {
        val file = File(convDir, "$sessionId.json")
        val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
        arr.put(JSONObject().apply {
            put("role", role)
            put("text", text)
            put("ts", now())
        })
        file.writeText(arr.toString(2))
    }

    fun loadConversation(sessionId: String): List<Pair<String, String>> {
        val file = File(convDir, "$sessionId.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                o.getString("role") to o.getString("text")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun listSessions(): List<String> =
        convDir.listFiles()?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension } ?: emptyList()

    // ── GitHub project mirror ──────────────────────────────

    fun saveGithubProject(repo: String, content: String) {
        val file = File(githubDir, "${sanitize(repo)}.md")
        file.writeText("# $repo\n\nUpdated: ${now()}\n\n$content\n")
        updateIndex("github", repo, file.absolutePath)
    }

    fun readGithubProject(repo: String): String? {
        val file = File(githubDir, "${sanitize(repo)}.md")
        return if (file.exists()) file.readText() else null
    }

    fun listGithubProjects(): List<String> =
        githubDir.listFiles()?.filter { it.extension == "md" }?.map {
            it.nameWithoutExtension.replace("_", "/")
        } ?: emptyList()

    // ── Index ──────────────────────────────────────────────

    private fun updateIndex(type: String, key: String, path: String, categories: List<String> = emptyList()) {
        val index = if (indexFile.exists()) JSONObject(indexFile.readText()) else JSONObject()
        val section = index.optJSONObject(type) ?: JSONObject()
        section.put(key, JSONObject().apply {
            put("path", path)
            put("updated", now())
            if (categories.isNotEmpty()) put("categories", JSONArray(categories))
        })
        index.put(type, section)
        index.put("last_updated", now())
        indexFile.writeText(index.toString(2))
    }

    fun getRootPath(): String = rootDir.absolutePath

    fun searchAll(query: String): List<String> {
        val q = query.lowercase()
        val hits = mutableListOf<String>()
        notesDir.listFiles()?.forEach { f ->
            if (f.readText().lowercase().contains(q)) hits.add("note:${f.nameWithoutExtension}")
        }
        listSites().forEach { s ->
            if (s.toString().lowercase().contains(q)) hits.add("site:${s.optString("host")}")
        }
        githubDir.listFiles()?.forEach { f ->
            if (f.readText().lowercase().contains(q)) hits.add("github:${f.nameWithoutExtension}")
        }
        return hits
    }

    private fun sanitize(name: String) =
        name.replace(Regex("[^a-zA-Z0-9._\u0600-\u06FF-]"), "_").take(80)

    private fun now() =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val TAG = "PersistentMemory"
    }
}
