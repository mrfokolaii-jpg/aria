package com.mrfokolaii.aria.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app chat history only (filesDir — NOT external storage / Grok folder).
 * Voice and text chat both write here so the AI never forgets the conversation.
 */
class ChatHistoryStore(context: Context) {

    private val dir = File(context.filesDir, "chat_history").also { it.mkdirs() }
    private val mainFile = File(dir, "$MAIN_SESSION.json")

    fun append(role: String, text: String, source: String = "chat") {
        val arr = loadArray()
        arr.put(JSONObject().apply {
            put("role", role)
            put("text", text)
            put("source", source) // "voice" or "chat"
            put("ts", now())
        })
        // Keep a large buffer so context is never lost (last 500 messages)
        while (arr.length() > MAX_MESSAGES) {
            arr.remove(0)
        }
        mainFile.writeText(arr.toString())
    }

    fun loadAll(): List<ChatMessage> {
        val arr = loadArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ChatMessage(
                role = o.optString("role", "user"),
                text = o.optString("text", ""),
                source = o.optString("source", "chat"),
                ts = o.optString("ts", "")
            )
        }.filter { it.text.isNotBlank() }
    }

    /** Full history as role/text pairs for the AI (no truncation for memory). */
    fun asHistoryPairs(limit: Int = MAX_CONTEXT_PAIRS): List<Pair<String, String>> {
        val all = loadAll()
        val slice = if (all.size > limit) all.takeLast(limit) else all
        return slice.map { it.role to it.text }
    }

    fun clear() {
        if (mainFile.exists()) mainFile.writeText("[]")
    }

    fun messageCount(): Int = loadArray().length()

    private fun loadArray(): JSONArray {
        if (!mainFile.exists()) return JSONArray()
        return try {
            JSONArray(mainFile.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun now() =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    data class ChatMessage(
        val role: String,
        val text: String,
        val source: String,
        val ts: String
    )

    companion object {
        const val MAIN_SESSION = "main"
        private const val MAX_MESSAGES = 500
        /** How many messages sent to the model as context */
        const val MAX_CONTEXT_PAIRS = 40
    }
}
