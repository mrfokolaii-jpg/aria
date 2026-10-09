package com.mrfokolaii.aria.agent.knowledge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * GitHub Knowledge Base Manager
 * Stores project metadata instead of copying entire repositories
 */
class KnowledgeBase(private val context: Context) {

    private val baseDir: File
        get() = File(context.filesDir, "aria-knowledge/github-projects").apply {
            if (!exists()) mkdirs()
        }

    /**
     * Check if project is already documented
     */
    suspend fun hasProject(repoFullName: String): Boolean = withContext(Dispatchers.IO) {
        val sanitized = sanitizeFileName(repoFullName)
        File(baseDir, "$sanitized.md").exists()
    }

    /**
     * Get project documentation
     */
    suspend fun getProject(repoFullName: String): ProjectKnowledge? = withContext(Dispatchers.IO) {
        val sanitized = sanitizeFileName(repoFullName)
        val file = File(baseDir, "$sanitized.md")

        if (!file.exists()) return@withContext null

        try {
            val content = file.readText()
            val metadata = parseMetadata(content)
            ProjectKnowledge(
                repoFullName = metadata["repo_full_name"] ?: repoFullName,
                url = metadata["url"] ?: "",
                purpose = metadata["purpose"] ?: "",
                description = content,
                language = metadata["language"] ?: "",
                lastReviewed = metadata["last_reviewed"] ?: "",
                technologies = metadata["technologies"]?.split(",")?.map { it.trim() } ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read project: $repoFullName", e)
            null
        }
    }

    /**
     * Save project knowledge
     */
    suspend fun saveProject(knowledge: ProjectKnowledge) = withContext(Dispatchers.IO) {
        val sanitized = sanitizeFileName(knowledge.repoFullName)
        val file = File(baseDir, "$sanitized.md")

        try {
            val content = buildString {
                appendLine("# ${knowledge.repoFullName}")
                appendLine()
                appendLine("**URL:** ${knowledge.url}")
                appendLine("**Language:** ${knowledge.language}")
                appendLine("**Technologies:** ${knowledge.technologies.joinToString(", ")}")
                appendLine("**Purpose:** ${knowledge.purpose}")
                appendLine("**Last Reviewed:** ${knowledge.lastReviewed}")
                appendLine()
                appendLine("## Description")
                appendLine()
                appendLine(knowledge.description)
                if (knowledge.architecture.isNotEmpty()) {
                    appendLine()
                    appendLine("## Architecture")
                    appendLine()
                    appendLine(knowledge.architecture)
                }
                if (knowledge.keyFiles.isNotEmpty()) {
                    appendLine()
                    appendLine("## Key Files")
                    appendLine()
                    knowledge.keyFiles.forEach { (path, desc) ->
                        appendLine("- `$path`: $desc")
                    }
                }
                if (knowledge.dependencies.isNotEmpty()) {
                    appendLine()
                    appendLine("## Dependencies")
                    appendLine()
                    knowledge.dependencies.forEach { dep ->
                        appendLine("- $dep")
                    }
                }
                if (knowledge.useCases.isNotEmpty()) {
                    appendLine()
                    appendLine("## Use Cases")
                    appendLine()
                    knowledge.useCases.forEachIndexed { i, useCase ->
                        appendLine("${i + 1}. $useCase")
                    }
                }
                if (knowledge.limitations.isNotEmpty()) {
                    appendLine()
                    appendLine("## Limitations")
                    appendLine()
                    knowledge.limitations.forEach { limit ->
                        appendLine("- $limit")
                    }
                }
                if (knowledge.notes.isNotEmpty()) {
                    appendLine()
                    appendLine("## Notes")
                    appendLine()
                    appendLine(knowledge.notes)
                }
            }

            file.writeText(content)
            Log.d(TAG, "Saved knowledge for: ${knowledge.repoFullName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save project: ${knowledge.repoFullName}", e)
        }
    }

    /**
     * Search knowledge base
     */
    suspend fun search(query: String): List<ProjectKnowledge> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ProjectKnowledge>()
        val lowerQuery = query.lowercase()

        baseDir.listFiles()?.forEach { file ->
            if (file.extension == "md") {
                try {
                    val content = file.readText().lowercase()
                    if (content.contains(lowerQuery)) {
                        val repoName = file.nameWithoutExtension.replace("_", "/")
                        getProject(repoName)?.let { results.add(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading ${file.name}", e)
                }
            }
        }

        results
    }

    /**
     * List all projects in knowledge base
     */
    suspend fun listAll(): List<String> = withContext(Dispatchers.IO) {
        baseDir.listFiles()?.mapNotNull { file ->
            if (file.extension == "md") {
                file.nameWithoutExtension.replace("_", "/")
            } else null
        } ?: emptyList()
    }

    private fun parseMetadata(content: String): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        val lines = content.lines()

        for (line in lines.take(20)) {
            when {
                line.startsWith("**URL:**") ->
                    metadata["url"] = line.substringAfter("**URL:**").trim()
                line.startsWith("**Language:**") ->
                    metadata["language"] = line.substringAfter("**Language:**").trim()
                line.startsWith("**Technologies:**") ->
                    metadata["technologies"] = line.substringAfter("**Technologies:**").trim()
                line.startsWith("**Purpose:**") ->
                    metadata["purpose"] = line.substringAfter("**Purpose:**").trim()
                line.startsWith("**Last Reviewed:**") ->
                    metadata["last_reviewed"] = line.substringAfter("**Last Reviewed:**").trim()
            }
        }

        return metadata
    }

    private fun sanitizeFileName(repoFullName: String): String {
        return repoFullName.replace("/", "_").replace(" ", "_")
    }

    companion object {
        private const val TAG = "KnowledgeBase"

        fun getCurrentTimestamp(): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        }
    }
}

data class ProjectKnowledge(
    val repoFullName: String,
    val url: String,
    val purpose: String,
    val description: String,
    val language: String,
    val lastReviewed: String,
    val technologies: List<String> = emptyList(),
    val architecture: String = "",
    val keyFiles: Map<String, String> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val useCases: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val notes: String = ""
)
