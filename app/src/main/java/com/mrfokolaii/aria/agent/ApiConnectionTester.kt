package com.mrfokolaii.aria.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiConnectionTester {
    data class Result(val success: Boolean, val message: String, val selectedModel: String? = null)

    fun test(apiKey: String, endpoint: String, model: String): Result {
        if (apiKey.isBlank()) return Result(false, "کلید API وارد نشده است.")
        if (endpoint.isBlank()) return Result(false, "آدرس API وارد نشده است.")
        if (model.isBlank()) return Result(false, "نام مدل وارد نشده است.")

        return try {
            val url = endpoint.trim().trimEnd('/').let {
                if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
            }

            val messages = JSONArray()
                .put(JSONObject().put("role", "user").put("content", "Reply with OK"))

            val body = JSONObject()
                .put("model", model.trim())
                .put("messages", messages)
                .put("max_tokens", 16)
                .put("stream", false)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result(false, "خطای HTTP ${response.code}: ${response.body?.string().orEmpty().take(180)}")
                } else {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val text = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        .orEmpty()

                    // Some models return empty/short content on tiny prompts — HTTP 200 is enough
                    if (text.isNotBlank()) Result(true, "اتصال موفق؛ پاسخ مدل دریافت شد.")
                    else Result(true, "اتصال برقرار است (پاسخ کوتاه/خالی از مدل).")
                }
            }
        } catch (e: Exception) {
            Result(false, "اتصال ناموفق: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }
}
