package com.mrfokolaii.aria.memory

import android.content.Context
import android.os.Environment
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Local knowledge base under Download/aria pdf
 * Supports: PDF, HTML, text (txt/md/csv/json), and saved links.
 */
class LocalKnowledgeStore(private val context: Context) {

    data class Doc(
        val name: String,
        val path: String,
        val text: String,
        val kind: String = "text"
    )

    data class LinkEntry(
        val url: String,
        val title: String,
        val summary: String,
        val savedAt: Long = System.currentTimeMillis()
    )

    fun primaryDir(): File {
        val dl = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            File("/storage/emulated/0/Download")
        }
        return File(dl, "aria pdf")
    }

    fun subDir(name: String): File = File(primaryDir(), name).also {
        try { it.mkdirs() } catch (_: Exception) {}
    }

    fun knowledgeDirs(): List<File> {
        val list = mutableListOf<File>()
        val primary = primaryDir()
        list += primary
        list += File(primary, "pdf")
        list += File(primary, "html")
        list += File(primary, "text")
        list += File(primary, "links")
        context.getExternalFilesDir("knowledge")?.let { list += it }
        list += File(context.filesDir, "knowledge")
        return list.distinctBy { it.absolutePath }
    }

    fun ensureFolders() {
        try { primaryDir().mkdirs() } catch (_: Exception) {}
        for (s in listOf("pdf", "html", "text", "links")) {
            try { subDir(s) } catch (_: Exception) {}
        }
        try { context.getExternalFilesDir("knowledge")?.mkdirs() } catch (_: Exception) {}
        try { File(context.filesDir, "knowledge").mkdirs() } catch (_: Exception) {}
        // Seed empty links index
        val index = File(subDir("links"), "links.md")
        if (!index.exists()) {
            try {
                index.writeText("# لینک‌های دانش آریا\n\n")
            } catch (_: Exception) {}
        }
    }

    private fun isKnowledgeFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".txt") || n.endsWith(".md") ||
            n.endsWith(".csv") || n.endsWith(".json") ||
            n.endsWith(".pdf") || n.endsWith(".html") || n.endsWith(".htm") ||
            n.endsWith(".url") || n.endsWith(".link")
    }

    private fun kindOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".pdf") -> "pdf"
            n.endsWith(".html") || n.endsWith(".htm") -> "html"
            n.endsWith(".url") || n.endsWith(".link") || n == "links.md" -> "link"
            else -> "text"
        }
    }

    fun listDocs(): List<Doc> {
        ensureFolders()
        val out = mutableListOf<Doc>()
        val seen = mutableSetOf<String>()
        for (dir in knowledgeDirs()) {
            if (!dir.exists() || !dir.isDirectory) continue
            collectFiles(dir).forEach { f ->
                if (!f.isFile || !isKnowledgeFile(f.name)) return@forEach
                val key = "${f.name.lowercase()}|${f.length()}|${f.parent}"
                if (!seen.add(key)) return@forEach
                val text = extractText(f)
                if (text.isNotBlank()) {
                    out += Doc(f.name, f.absolutePath, text, kindOf(f.name))
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun collectFiles(dir: File, depth: Int = 0): List<File> {
        if (depth > 3) return emptyList()
        val files = mutableListOf<File>()
        dir.listFiles()?.forEach { f ->
            when {
                f.isFile -> files += f
                f.isDirectory -> files += collectFiles(f, depth + 1)
            }
        }
        return files
    }

    fun statusText(): String {
        ensureFolders()
        val docs = listDocs()
        val primary = primaryDir().absolutePath
        val byKind = docs.groupBy { it.kind }
        return if (docs.isEmpty()) {
            buildString {
                appendLine("دانش خالی است.")
                appendLine("پوشه:")
                appendLine(primary)
                appendLine()
                appendLine("فرمت‌های پشتیبانی‌شده:")
                appendLine("• PDF → پوشه pdf/")
                appendLine("• HTML → پوشه html/")
                appendLine("• متن txt/md/csv/json → پوشه text/")
                appendLine("• لینک → بگو «این لینک را ذخیره کن» یا فایل در links/")
            }
        } else {
            buildString {
                appendLine("${docs.size} مورد دانش:")
                appendLine("PDF: ${byKind["pdf"]?.size ?: 0} | HTML: ${byKind["html"]?.size ?: 0} | متن: ${byKind["text"]?.size ?: 0} | لینک: ${byKind["link"]?.size ?: 0}")
                appendLine()
                docs.take(30).forEach { d ->
                    appendLine("• [${d.kind}] ${d.name}")
                }
                if (docs.size > 30) appendLine("… و ${docs.size - 30} مورد دیگر")
                appendLine()
                appendLine("پوشه اصلی:")
                append(primary)
            }
        }
    }

    fun retrieveRelevant(query: String, maxChars: Int = 3200): String {
        val docs = listDocs()
        if (docs.isEmpty()) return ""

        val q = query.lowercase()
            .replace("\u200c", " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
        if (q.isEmpty()) {
            return docs.take(3).joinToString("\n\n") {
                "[${it.kind}:${it.name}]\n${it.text.take(700)}"
            }.take(maxChars)
        }

        data class Scored(val score: Int, val snippet: String)
        val scored = mutableListOf<Scored>()
        for (doc in docs) {
            val lower = doc.text.lowercase()
            val nameL = doc.name.lowercase()
            var score = 0
            for (w in q) {
                if (lower.contains(w)) score += 2
                if (nameL.contains(w)) score += 4
            }
            if (score == 0) continue
            val idx = q.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: 0
            val start = (idx - 180).coerceAtLeast(0)
            val snip = doc.text.substring(start, (start + 1000).coerceAtMost(doc.text.length))
            scored += Scored(score, "[${doc.kind}:${doc.name}]\n$snip")
        }
        if (scored.isEmpty()) {
            return docs.take(2).joinToString("\n\n") {
                "[${it.kind}:${it.name}]\n${it.text.take(500)}"
            }.take(maxChars)
        }
        return scored.sortedByDescending { it.score }.take(5)
            .joinToString("\n\n") { it.snippet }.take(maxChars)
    }

    /** Save plain text note into text/ folder. */
    fun saveTextNote(title: String, body: String): File {
        ensureFolders()
        val safe = sanitize(title).ifBlank { "note_${System.currentTimeMillis()}" }
        val file = File(subDir("text"), "$safe.md")
        val content = buildString {
            appendLine("# $title")
            appendLine()
            appendLine(body.trim())
            appendLine()
            appendLine("<!-- saved: ${System.currentTimeMillis()} -->")
        }
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /** Save a link (+ optional fetched page text) into links/. */
    fun saveLink(url: String, title: String = "", fetchPage: Boolean = true): LinkEntry {
        ensureFolders()
        val cleanUrl = url.trim()
        var pageTitle = title.trim()
        var summary = ""
        if (fetchPage) {
            try {
                val html = httpGet(cleanUrl)
                if (html.isNotBlank()) {
                    val doc = Jsoup.parse(html, cleanUrl)
                    if (pageTitle.isBlank()) pageTitle = doc.title().ifBlank { hostOf(cleanUrl) }
                    summary = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.take(4000).orEmpty()
                    // Also store full extract as html-side text file
                    val safe = sanitize(pageTitle.ifBlank { hostOf(cleanUrl) })
                    File(subDir("links"), "$safe.txt").writeText(
                        "URL: $cleanUrl\nTITLE: $pageTitle\n\n$summary",
                        Charsets.UTF_8
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch link: ${e.message}")
            }
        }
        if (pageTitle.isBlank()) pageTitle = hostOf(cleanUrl)

        val index = File(subDir("links"), "links.md")
        val line = "- [${pageTitle}]($cleanUrl) — ${summary.take(160)}\n"
        try {
            if (!index.exists()) index.writeText("# لینک‌های دانش آریا\n\n")
            // Avoid duplicate URL lines
            val existing = index.readText()
            if (!existing.contains(cleanUrl)) {
                index.appendText(line)
            }
        } catch (e: Exception) {
            Log.w(TAG, "index link: ${e.message}")
        }
        return LinkEntry(cleanUrl, pageTitle, summary)
    }

    /** Import any supported file into the right subfolder (copy). */
    fun importFile(source: File): File? {
        if (!source.isFile || !isKnowledgeFile(source.name)) return null
        ensureFolders()
        val destDir = when (kindOf(source.name)) {
            "pdf" -> subDir("pdf")
            "html" -> subDir("html")
            "link" -> subDir("links")
            else -> subDir("text")
        }
        val dest = File(destDir, source.name)
        return try {
            source.copyTo(dest, overwrite = true)
            dest
        } catch (e: Exception) {
            Log.w(TAG, "import: ${e.message}")
            null
        }
    }

    private fun extractText(file: File): String {
        return try {
            val name = file.name.lowercase()
            when {
                name.endsWith(".pdf") -> extractPdfRough(file)
                name.endsWith(".html") || name.endsWith(".htm") -> extractHtml(file)
                name.endsWith(".url") || name.endsWith(".link") -> extractUrlFile(file)
                else -> file.readText(Charsets.UTF_8).replace("\u0000", " ").trim()
            }.take(60_000)
        } catch (e: Exception) {
            Log.w(TAG, "extract ${file.name}: ${e.message}")
            ""
        }
    }

    private fun extractHtml(file: File): String {
        val raw = file.readText(Charsets.UTF_8)
        return try {
            val doc = Jsoup.parse(raw)
            val title = doc.title()
            val body = doc.body()?.text()?.replace(Regex("\\s+"), " ").orEmpty()
            buildString {
                if (title.isNotBlank()) appendLine("عنوان: $title")
                append(body)
            }.trim()
        } catch (_: Exception) {
            raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        }
    }

    private fun extractUrlFile(file: File): String {
        val raw = file.readText(Charsets.UTF_8)
        val url = Regex("https?://\\S+").find(raw)?.value ?: raw.trim()
        return "لینک: $url\n$raw".take(5000)
    }

    /** Best-effort PDF text (no native PDF lib — stream scan + UTF-8 windows). */
    private fun extractPdfRough(file: File): String {
        val bytes = try { file.readBytes() } catch (_: Exception) { return "" }
        val sb = StringBuilder()
        // 1) Latin + high-byte runs
        val cur = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c >= 0xC0) {
                cur.append(c.toChar())
            } else {
                if (cur.length >= 5) {
                    val s = cur.toString()
                    if (!s.startsWith("%PDF") && !s.contains("endobj") &&
                        !s.contains("stream") && s.any { it.isLetter() }
                    ) {
                        sb.append(s).append(' ')
                    }
                }
                cur.clear()
            }
            if (sb.length > 45_000) break
        }
        if (cur.length >= 5) sb.append(cur)

        // 2) Try UTF-8 decode of whole file for Persian PDFs that embed unicode
        if (sb.length < 80) {
            try {
                val utf = String(bytes, Charsets.UTF_8)
                val cleaned = utf.replace("\u0000", " ")
                    .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (cleaned.length > sb.length) return cleaned.take(50_000)
            } catch (_: Exception) {}
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun httpGet(url: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AriaKnowledge/1.0")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            return resp.body?.string().orEmpty()
        }
    }

    private fun hostOf(url: String): String = try {
        URI(url).host ?: "link"
    } catch (_: Exception) {
        "link"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .take(80)
            .trim('_')
            .ifBlank { "item" }

    companion object {
        private const val TAG = "LocalKnowledgeStore"
    }
}
