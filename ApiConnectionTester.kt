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
                .put("max_tokens", 8)
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

                    if (text.isNotBlank()) Result(true, "اتصال موفق؛ پاسخ مدل دریافت شد.")
                    else Result(false, "پاسخ دریافت شد، اما متن مدل خالی است.")
                }
            }
        } catch (e: Exception) {
            Result(false, "اتصال ناموفق: ${e.javaClass.simpleName}: ${e.message ?: "خطا"}")
        }
    }

    fun testUnoRouter(apiKey: String, endpoint: String): Result {
        if (apiKey.isBlank()) return Result(false, "کلید API وارد نشده است.")
        if (endpoint.isBlank()) return Result(false, "آدرس API وارد نشده است.")
        return try {
            val baseUrl = endpoint.trim().trimEnd('/')
                .removeSuffix("/chat/completions")
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()

            val modelsRequest = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val modelIds = client.newCall(modelsRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result(false, "دریافت فهرست مدل‌ها ناموفق بود؛ HTTP ${response.code}. کلید و دسترسی UnoRouter را بررسی کن.")
                }
                val data = JSONObject(response.body?.string() ?: "{}")
                    .optJSONArray("data") ?: JSONArray()
                val free = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    if (id.isBlank()) continue
                    val pricing = item.optJSONObject("pricing") ?: continue
                    val input = pricing.optDouble("input", -1.0)
                    val output = pricing.optDouble("output", -1.0)
                    if (input == 0.0 && output == 0.0) free.add(id)
                }
                free
            }

            if (modelIds.isEmpty()) {
                return Result(false, "مدل رایگان قابل‌شناسایی پیدا نشد. هیچ مدل پولی انتخاب نشد.")
            }

            var lastError = "مدل رایگان پاسخ نداد."
            for (model in modelIds) {
                val url = if (baseUrl.endsWith("/chat/completions")) baseUrl
                    else "$baseUrl/chat/completions"
                val messages = JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "Reply with OK"))
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("max_tokens", 8)
                    .put("stream", false)
                    .toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val answer = json.optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("message")
                            ?.optString("content", "").orEmpty()
                        if (answer.isNotBlank()) {
                            return Result(true, "اتصال موفق؛ مدل رایگان انتخاب شد: $model", model)
                        }
                        lastError = "پاسخ مدل رایگان خالی بود."
                    } else {
                        lastError = "مدل رایگان با HTTP ${response.code} پاسخ داد."
                    }
                }
            }
            Result(false, lastError)
        } catch (e: Exception) {
            Result(false, "اتصال ناموفق: ${e.javaClass.simpleName}")
        }
    }

}
