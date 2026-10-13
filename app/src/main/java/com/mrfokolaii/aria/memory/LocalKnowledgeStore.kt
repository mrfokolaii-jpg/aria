package com.mrfokolaii.aria.memory

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Knowledge from ONE primary folder: Download/aria pdf
 * (+ app private knowledge mirror).
 */
class LocalKnowledgeStore(private val context: Context) {

    data class Doc(val name: String, val path: String, val text: String)

    /** Canonical user folder only. */
    fun primaryDir(): File {
        val dl = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            File("/storage/emulated/0/Download")
        }
        return File(dl, "aria pdf")
    }

    fun knowledgeDirs(): List<File> {
        val list = mutableListOf<File>()
        list += primaryDir()
        context.getExternalFilesDir("knowledge")?.let { list += it }
        list += File(context.filesDir, "knowledge")
        return list.distinctBy { it.absolutePath }
    }

    fun ensureFolders() {
        try { primaryDir().mkdirs() } catch (_: Exception) {}
        try {
            context.getExternalFilesDir("knowledge")?.mkdirs()
        } catch (_: Exception) {}
    }

    fun listDocs(): List<Doc> {
        val out = mutableListOf<Doc>()
        val seen = mutableSetOf<String>() // name|size
        for (dir in knowledgeDirs()) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name.lowercase()
                if (!name.endsWith(".txt") && !name.endsWith(".md") &&
                    !name.endsWith(".csv") && !name.endsWith(".json") &&
                    !name.endsWith(".pdf") && !name.endsWith(".html")
                ) return@forEach
                val key = "${f.name.lowercase()}|${f.length()}"
                if (!seen.add(key)) return@forEach
                val text = extractText(f)
                if (text.isNotBlank()) {
                    out += Doc(f.name, f.absolutePath, text)
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun statusText(): String {
        ensureFolders()
        val docs = listDocs()
        val primary = primaryDir().absolutePath
        return if (docs.isEmpty()) {
            "دانش خالی است.\nفقط این پوشه را استفاده کن:\n$primary\n\nفرمت: txt, md, pdf, json"
        } else {
            "${docs.size} فایل دانش (بدون تکرار):\n" +
                docs.joinToString("\n") { "• ${it.name}" } +
                "\n\nپوشه اصلی:\n$primary"
        }
    }

    fun retrieveRelevant(query: String, maxChars: Int = 2500): String {
        val docs = listDocs()
        if (docs.isEmpty()) return ""

        val q = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (q.isEmpty()) {
            return docs.take(2).joinToString("\n\n") { "[${it.name}]\n${it.text.take(800)}" }.take(maxChars)
        }

        data class Scored(val score: Int, val snippet: String)
        val scored = mutableListOf<Scored>()
        for (doc in docs) {
            val lower = doc.text.lowercase()
            var score = 0
            for (w in q) if (lower.contains(w)) score += 2
            if (doc.name.lowercase().let { n -> q.any { n.contains(it) } }) score += 5
            if (score == 0) continue
            val idx = q.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: 0
            val start = (idx - 200).coerceAtLeast(0)
            val snip = doc.text.substring(start, (start + 900).coerceAtMost(doc.text.length))
            scored += Scored(score, "[${doc.name}]\n$snip")
        }
        if (scored.isEmpty()) {
            return docs.take(2).joinToString("\n\n") { "[${it.name}]\n${it.text.take(600)}" }.take(maxChars)
        }
        return scored.sortedByDescending { it.score }.take(4)
            .joinToString("\n\n") { it.snippet }.take(maxChars)
    }

    private fun extractText(file: File): String {
        return try {
            val name = file.name.lowercase()
            when {
                name.endsWith(".pdf") -> extractPdfRough(file)
                else -> file.readText(Charsets.UTF_8).replace("\u0000", " ").trim()
            }.take(50_000)
        } catch (e: Exception) {
            Log.w(TAG, "extract ${file.name}: ${e.message}")
            ""
        }
    }

    private fun extractPdfRough(file: File): String {
        val bytes = try { file.readBytes() } catch (_: Exception) { return "" }
        val sb = StringBuilder()
        val cur = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c >= 0xC0) {
                cur.append(c.toChar())
            } else {
                if (cur.length >= 4) {
                    val s = cur.toString()
                    if (!s.startsWith("%PDF") && !s.contains("endobj") && s.any { it.isLetter() }) {
                        sb.append(s).append(' ')
                    }
                }
                cur.clear()
            }
            if (sb.length > 40_000) break
        }
        if (cur.length >= 4) sb.append(cur)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private const val TAG = "LocalKnowledgeStore"
    }
}
