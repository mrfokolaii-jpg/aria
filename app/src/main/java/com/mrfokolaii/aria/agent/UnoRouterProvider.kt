package com.mrfokolaii.aria.agent

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Unified AI router supporting OpenAI-compatible endpoints:
 * Groq, OpenRouter, xAI (Grok), and custom proxies.
 */
class UnoRouterProvider(
    private val apiKey: String,
    private val endpoint: String,
    private var model: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ChatResponse(val text: String, val usedModel: String)
    data class ModelInfo(val id: String, val name: String = id)

    fun chat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        systemPrompt: String = DEFAULT_SYSTEM
    ): ChatResponse {
        if (apiKey.isBlank()) {
            return ChatResponse("لطفاً ابتدا کلید API را در تنظیمات وارد و تست کنید.", model)
        }

        return try {
            val base = endpoint.trim().trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base
            else "$base/chat/completions"

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

            // Keep a long context so the assistant does not forget prior turns
            history.takeLast(40).forEach { (role, content) ->
                val r = if (role == "user") "user" else "assistant"
                // Skip duplicate of the current user message if already in history
                if (r == "user" && content == userMessage) return@forEach
                messages.put(JSONObject().put("role", r).put("content", content))
            }
            messages.put(JSONObject().put("role", "user").put("content", userMessage))

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", 1024)
                .put("temperature", 0.7)
                .put("stream", false)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code}: $raw")
                    return ChatResponse(
                        "خطا در ارتباط با هوش مصنوعی (کد ${response.code}). کلید و مدل را بررسی کنید.",
                        model
                    )
                }
                val json = JSONObject(raw)
                val text = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    .orEmpty()

                if (text.isBlank()) {
                    ChatResponse("پاسخ خالی از مدل دریافت شد.", model)
                } else {
                    ChatResponse(text, model)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            ChatResponse("خطا: ${e.localizedMessage ?: e.javaClass.simpleName}", model)
        }
    }

    fun fetchAvailableModels(): List<ModelInfo> {
        if (apiKey.isBlank()) return emptyList()
        return try {
            val base = endpoint.trim().trimEnd('/')
                .removeSuffix("/chat/completions")
            val url = "$base/models"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val data = JSONObject(response.body?.string() ?: "{}")
                    .optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<ModelInfo>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    if (id.isNotBlank()) {
                        list.add(ModelInfo(id))
                    }
                }
                list.sortedBy { it.id }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAvailableModels failed: ${e.message}")
            emptyList()
        }
    }

    fun updateModel(newModel: String) {
        if (newModel.isNotBlank()) model = newModel.trim()
    }

    companion object {
        private const val TAG = "UnoRouterProvider"

        const val DEFAULT_SYSTEM = """
تو آریا هستی، یک دستیار هوشمند فارسی‌زبان روی اندروید.
پاسخ‌هایت را کوتاه، واضح و دوستانه به فارسی بده.
تاریخچه مکالمه به تو داده می‌شود؛ همیشه آن را در نظر بگیر و چیزی را فراموش نکن.
اگر نیاز به ابزار داری (جستجو، باز کردن برنامه و ...) مستقیم و شفاف عمل کن.
""".trim()

        const val GROQ_BASE = "https://api.groq.com/openai/v1"
        const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"
        const val XAI_BASE = "https://api.x.ai/v1"

        val GROQ_DEFAULT_MODELS = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "gemma2-9b-it",
            "mixtral-8x7b-32768"
        )

        val XAI_DEFAULT_MODELS = listOf(
            "grok-4.7",
            "grok-3",
            "grok-3-mini",
            "grok-2"
        )
    }
}
