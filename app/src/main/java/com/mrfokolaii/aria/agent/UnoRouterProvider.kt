package com.mrfokolaii.aria.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UnoRouterProvider(
    private val apiKey: String,
    private val endpoint: String = "https://api.unorouter.com/v1",
    private val preferredModel: String = "claude-opus-4.7"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun chatUrl(): String {
        val value = endpoint.trim().trimEnd('/')
        return if (value.endsWith("/chat/completions")) value
        else "$value/chat/completions"
    }

    private fun modelsUrl(): String {
        val value = endpoint.trim().trimEnd('/')
        val base = value.removeSuffix("/chat/completions")
        return "$base/models"
    }

    data class ModelInfo(
        val id: String,
        val name: String,
        val isFree: Boolean,
        val provider: String
    )

    suspend fun fetchAvailableModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(modelsUrl())
                .header("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Model list HTTP ${response.code}")
                    return@withContext emptyList()
                }

                val data = JSONObject(response.body?.string() ?: "{}")
                    .optJSONArray("data") ?: JSONArray()

                buildList {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id", "")
                        if (id.isBlank()) continue
                        val pricing = item.optJSONObject("pricing")
                        add(
                            ModelInfo(
                                id = id,
                                name = item.optString("name", id),
                                isFree = pricing?.optDouble("input", -1.0) == 0.0 &&
                                    pricing.optDouble("output", -1.0) == 0.0,
                                provider = item.optString("owned_by", "unknown")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model list failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    suspend fun chat(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        preferredModel: String? = null
    ): ChatResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ChatResponse("", null, "API key is empty")
        }

        val model = preferredModel?.takeIf { it.isNotBlank() }
            ?: this@UnoRouterProvider.preferredModel

        try {
            val messages = JSONArray()
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "تو آریا هستی؛ دستیار صوتی فارسی کاربر. " +
                            "صمیمی، کوتاه، دقیق و محاوره‌ای جواب بده."
                    )
            )

            history.takeLast(8).forEach { (role, content) ->
                messages.put(
                    JSONObject()
                        .put("role", if (role == "user") "user" else "assistant")
                        .put("content", content)
                )
            }
            messages.put(JSONObject().put("role", "user").put("content", prompt))

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.7)
                .put("max_tokens", 700)
                .put("stream", true)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(chatUrl())
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty().take(300)
                    return@withContext ChatResponse(
                        "",
                        model,
                        "HTTP ${response.code}: $detail"
                    )
                }

                val raw = response.body?.string().orEmpty()
                val result = StringBuilder()

                if (raw.trimStart().startsWith("data:")) {
                    raw.lineSequence().forEach { line ->
                        val data = line.trim().removePrefix("data:").trim()
                        if (data.isBlank() || data == "[DONE]") return@forEach
                        try {
                            val delta = JSONObject(data)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content", "")
                                .orEmpty()
                            result.append(delta)
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    val choices = JSONObject(raw).optJSONArray("choices")
                    result.append(
                        choices?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                            .orEmpty()
                    )
                }

                val text = result.toString().trim()
                if (text.isBlank()) {
                    ChatResponse("", model, "پاسخ خالی دریافت شد")
                } else {
                    ChatResponse(text, model, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat request failed", e)
            ChatResponse("", model, "${e.javaClass.simpleName}: ${e.message ?: "خطا"}")
        }
    }

    data class ChatResponse(
        val text: String,
        val usedModel: String?,
        val error: String?
    )

    companion object {
        private const val TAG = "UnoRouterProvider"
    }
}
