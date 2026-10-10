package com.mrfokolaii.aria.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AiAgentClient {
    fun prompt(context: Context, userPrompt: String): String {
        val prefs = context.getSharedPreferences("aria", Context.MODE_PRIVATE)
        val activeProvider = prefs.getString("active_provider", "groq") ?: "groq"
        
        val (apiKey, apiUrl, modelName) = when (activeProvider) {
            "groq" -> arrayOf(
                prefs.getString("groq_api_key", "") ?: "",
                "https://api.groq.com/openai/v1/chat/completions",
                "llama-3.3-70b-versatile"
            )
            "openrouter" -> arrayOf(
                prefs.getString("openrouter_api_key", "") ?: "",
                "https://openrouter.ai/api/v1/chat/completions",
                prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "google/gemini-flash-1.5"
            )
            else -> arrayOf(
                prefs.getString("custom_api_key", "") ?: "",
                prefs.getString("custom_api_url", "") ?: "",
                prefs.getString("custom_model", "") ?: ""
            )
        }

        if (apiKey.isBlank() || apiUrl.isBlank()) {
            return "لطفاً ابتدا کلید API را در تنظیمات وارد و تست کنید."
        }

        return try {
            val url = URL(apiUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val jsonBody = JSONObject().apply {
                put("model", modelName)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }

            if (responseCode in 200..299) {
                val jsonObj = JSONObject(response)
                val choices = jsonObj.getJSONArray("choices")
                val message = choices.getJSONObject(0).getJSONObject("message")
                message.getString("content").trim()
            } else {
                "خطا در ارتباط با هوش مصنوعی: کد $responseCode"
            }
        } catch (e: Exception) {
            "خطا: ${e.localizedMessage}"
        }
    }
}
