package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.tools.AgentToolManager
import com.mrfokolaii.aria.agent.tools.ToolResult
import com.mrfokolaii.aria.memory.PersistentMemory
import com.mrfokolaii.aria.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bridge: local tools + offline LLM + knowledge + internet search. */
class AgentBridge(
    private val context: Context,
    apiKey: String,
    endpoint: String,
    model: String
) {
    private val toolManager = AgentToolManager(context)
    private val offlineLlm = OfflineLlmEngine(context)
    private val knowledgeStore = PersistentMemory(context)

    suspend fun processWithAgentTools(
        userInput: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onStatus: (String) -> Unit = {}
    ): AgentResponse = withContext(Dispatchers.IO) {

        val online = NetworkUtils.isOnline(context)
        val lower = userInput.lowercase().replace("\u200c", "")

        if (containsAny(lower, "لیست دانش", "فایل دانش", "دانش من", "بروزرسانی دانش")) {
            return@withContext AgentResponse(text = knowledgeStore.statusText())
        }

        val toolIntent = detectToolIntent(userInput)

        if (toolIntent != null) {
            Log.d(TAG, "Tool intent: ${toolIntent.toolName} online=$online")

            if (!online && isOnlineOnlyTool(toolIntent.toolName)) {
                return@withContext AgentResponse(
                    text = "این کار به اینترنت نیاز دارد. بدون نت می‌تونم برنامه باز کنم، تماس بگیرم، پیامک بفرستم و از دانش محلی جواب بدهم."
                )
            }

            val statusMsg = when (toolIntent.toolName) {
                "web_search" -> "🔍 در حال جستجو در اینترنت…"
                "github_search" -> "🐙 در حال جستجو در گیت‌هاب…"
                "web_browse" -> "🌐 در حال باز کردن سایت…"
                "file_download" -> "⬇️ در حال دانلود…"
                "phone_call" -> "📞 در حال آماده‌سازی تماس…"
                "sms" -> "💬 در حال آماده‌سازی پیامک…"
                "app_launcher" -> "📱 در حال باز کردن برنامه…"
                else -> "⚙️ در حال اجرای ابزار…"
            }
            try { onStatus(statusMsg) } catch (_: Exception) {}

            val toolResult = if (toolIntent.useRouter) {
                toolManager.routeRequest(userInput, toolIntent.params)
            } else {
                toolManager.executeToolByName(toolIntent.toolName, toolIntent.params)
            }

            return@withContext if (toolResult.success) {
                val reply = when (toolIntent.toolName) {
                    "web_browse", "app_launcher", "phone_call", "sms", "file_download" ->
                        offlineToolReply(toolIntent.toolName, toolResult)
                    "github_search" -> {
                        val raw = formatToolResult(toolResult)
                        try {
                            try { onStatus("🧠 نتایج گیت‌هاب آماده — در حال فکر کردن و خلاصه‌سازی…") } catch (_: Exception) {}
                            offlineLlm.chat(
                                "نتایج گیت‌هاب:\n$raw\n\nدرخواست: $userInput\nکوتاه فارسی خلاصه کن.",
                                emptyList()
                            )
                        } catch (_: Exception) {
                            raw.take(1500)
                        }
                    }
                    "web_search" -> {
                        val raw = offlineToolReply("web_search", toolResult)
                        try {
                            try { onStatus("🧠 نتایج جستجو آماده — در حال فکر کردن و خلاصه‌سازی…") } catch (_: Exception) {}
                            offlineLlm.chat(
                                "نتایج جستجو:\n$raw\n\nسؤال: $userInput\nخیلی کوتاه فارسی جواب بده.",
                                emptyList()
                            )
                        } catch (_: Exception) {
                            raw.take(1200)
                        }
                    }
                    else -> formatToolResult(toolResult)
                }
                AgentResponse(text = reply, toolResult = toolResult)
            } else {
                AgentResponse(
                    text = toolResult.error ?: "ابزار ناموفق بود.",
                    toolResult = toolResult
                )
            }
        }

        try { onStatus("🧠 در حال فکر کردن و خواندن حافظه…") } catch (_: Exception) {}

        val prompt = userInput
        val text = offlineLlm.chat(prompt, conversationHistory)
        AgentResponse(text = text)
    }

    private fun isOnlineOnlyTool(name: String): Boolean = when (name) {
        "web_search", "file_download", "github_search", "youtube_search",
        "reddit_search", "rss_reader", "instagram_search", "bilibili_search" -> true
        else -> false
    }

    private fun formatToolResult(toolResult: ToolResult): String {
        val data = toolResult.data
        return when (data) {
            is List<*> -> data.take(8).joinToString("\n") { item ->
                when (item) {
                    is Map<*, *> -> {
                        val title = item["title"] ?: item["name"] ?: ""
                        val url = item["url"] ?: ""
                        val snip = item["snippet"] ?: item["description"] ?: ""
                        "• $title\n  $url\n  $snip"
                    }
                    else -> item.toString()
                }
            }
            is Map<*, *> -> {
                val results = data["results"]
                if (results is List<*>) {
                    results.take(8).joinToString("\n") { item ->
                        if (item is Map<*, *>) {
                            val title = item["title"] ?: item["name"] ?: ""
                            val url = item["url"] ?: ""
                            val snip = item["snippet"] ?: item["description"] ?: ""
                            val stars = item["stars"]
                            val starTxt = if (stars != null) " ⭐$stars" else ""
                            "• $title$starTxt\n  $url\n  $snip"
                        } else item.toString()
                    }
                } else data.toString()
            }
            else -> data?.toString() ?: (toolResult.error ?: "")
        }
    }

    private fun offlineToolReply(toolName: String, toolResult: ToolResult): String {
        return when (toolName) {
            "web_search" -> formatToolResult(toolResult)
            else -> formatToolResult(toolResult).ifBlank { "انجام شد." }
        }
    }

    private fun detectToolIntent(input: String): ToolIntent? {
        val lower = input.lowercase().replace("\u200c", "")

        if (containsAny(lower, "github", "گیتهاب", "گیت‌هاب", "گیتهب", "گیت هاب")) {
            val onlyOpen = containsAny(lower, "باز کن", "بازکن", "برو به سایت") &&
                !containsAny(lower, "سرچ", "جستجو", "search", "بگرد", "پیدا")
            if (!onlyOpen) {
                val q = input
                    .replace(Regex("(گیت.?هاب|گیتهاب|github)", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("(سرچ|جستجو|بگرد|لطفا|search)", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { input.trim().ifBlank { "open source" } }
                return ToolIntent("github_search", mapOf("action" to "search", "query" to q))
            }
        }

        if (containsAny(lower, "جستجو", "سرچ", "search", "گوگل کن", "بگرد")) {
            return ToolIntent("web_search", mapOf("query" to extractQuery(input)), useRouter = false)
        }

        if (containsAny(lower, "سایت", "browse", "http")) {
            extractUrl(input)?.let { url ->
                return ToolIntent("web_browse", mapOf("action" to "open", "url" to url))
            }
        }

        return null
    }

    private fun extractQuery(input: String): String =
        input.replace(Regex("(جستجو|سرچ|search|google|گوگل\\s*کن|بگرد)\\s*", RegexOption.IGNORE_CASE), "").trim()
            .ifBlank { input.trim() }

    private fun extractUrl(input: String): String? {
        val m = Regex("https?://\\S+").find(input)
        return m?.value
    }

    private fun containsAny(text: String, vararg keys: String): Boolean =
        keys.any { text.contains(it) }

    private data class ToolIntent(
        val toolName: String,
        val params: Map<String, Any>,
        val useRouter: Boolean = false
    )

    companion object {
        private const val TAG = "AgentBridge"
    }
}

data class AgentResponse(
    val text: String,
    val toolResult: ToolResult? = null
)
