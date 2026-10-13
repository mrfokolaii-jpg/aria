package com.mrfokolaii.aria.memory

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app chat history only (filesDir — NOT external storage).
 * Voice and text both write here.
 */
class ChatHistoryStore(private val context: Context) {

    private val dir = File(context.filesDir, "chat_history").also { it.mkdirs() }
    private val mainFile = File(dir, "$MAIN_SESSION.json")

    fun append(role: String, text: String, source: String = "chat") {
        val arr = loadArray()
        arr.put(JSONObject().apply {
            put("role", role)
            put("text", text)
            put("source", source)
            put("ts", now())
        })
        while (arr.length() > MAX_MESSAGES) {
            arr.remove(0)
        }
        mainFile.writeText(arr.toString())
        try {
            context.sendBroadcast(
                Intent(ACTION_CHAT_UPDATED).setPackage(context.packageName)
            )
        } catch (_: Exception) {}
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

    fun asHistoryPairs(limit: Int = MAX_CONTEXT_PAIRS): List<Pair<String, String>> {
        val all = loadAll()
        val slice = if (all.size > limit) all.takeLast(limit) else all
        return slice.map { it.role to it.text }
    }

    fun clear() {
        if (mainFile.exists()) mainFile.writeText("[]")
        try {
            context.sendBroadcast(
                Intent(ACTION_CHAT_UPDATED).setPackage(context.packageName)
            )
        } catch (_: Exception) {}
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
        const val ACTION_CHAT_UPDATED = "com.mrfokolaii.aria.CHAT_UPDATED"
        private const val MAX_MESSAGES = 500
        const val MAX_CONTEXT_PAIRS = 40
    }
}
