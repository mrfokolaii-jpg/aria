package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.tools.ToolResult
import com.mrfokolaii.aria.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge between ARIA service and Agent Tools.
 * Online: AI + web tools. Offline: local device tools still work.
 */
class AgentBridge(
    private val context: Context,
    apiKey: String,
    endpoint: String,
    model: String
) {

    private val toolManager = AgentToolManager(context)
    private val unoRouter = UnoRouterProvider(apiKey, endpoint, model)
    private var lastUsedModel: String? = null

    fun setAccessibilityService(service: android.accessibilityservice.AccessibilityService?) {
        toolManager.setAccessibilityService(service)
    }

    suspend fun processWithAgentTools(
        userInput: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): AgentResponse = withContext(Dispatchers.IO) {

        val online = NetworkUtils.isOnline(context)
        val toolIntent = detectToolIntent(userInput)

        if (toolIntent != null) {
            Log.d(TAG, "Tool intent: ${toolIntent.toolName} online=$online")

            // Tools that need the internet
            if (!online && isOnlineOnlyTool(toolIntent.toolName)) {
                return@withContext AgentResponse(
                    text = "الان اینترنت قطع است. این کار (جستجو/وب) بدون اینترنت ممکن نیست. " +
                            "ولی می‌تونم برنامه باز کنم، تماس بگیرم، پیامک بفرستم یا کنترل صفحه انجام بدم."
                )
            }

            val toolResult = if (toolIntent.useRouter) {
                toolManager.routeRequest(userInput, toolIntent.params)
            } else {
                toolManager.executeToolByName(toolIntent.toolName, toolIntent.params)
            }

            return@withContext if (toolResult.success) {
                val reply = if (online) {
                    try {
                        generateResponseWithToolData(userInput, toolResult)
                    } catch (_: Exception) {
                        offlineToolReply(toolIntent.toolName, toolResult)
                    }
                } else {
                    offlineToolReply(toolIntent.toolName, toolResult)
                }
                AgentResponse(
                    text = reply,
                    usedTool = toolIntent.toolName,
                    toolResult = toolResult
                )
            } else {
                AgentResponse(
                    text = "متأسفم، نتونستم این کار رو انجام بدم: ${toolResult.error}",
                    usedTool = toolIntent.toolName,
                    toolResult = toolResult
                )
            }
        }

        // Free chat — needs AI / network
        if (!online) {
            return@withContext AgentResponse(
                text = "اینترنت قطع است؛ برای گفتگوی آزاد به اینترنت نیاز دارم. " +
                        "در حالت آفلاین می‌تونی بگی: باز کن تلگرام، تماس بگیر، پیامک بفرست، صفحه رو بخون، اسکرول کن."
            )
        }

        val chatResponse = unoRouter.chat(userInput, conversationHistory)
        lastUsedModel = chatResponse.usedModel
        AgentResponse(text = chatResponse.text)
    }

    private fun isOnlineOnlyTool(name: String): Boolean = when (name) {
        "web_search", "web_browse", "github_search", "youtube_search",
        "reddit_search", "rss_reader", "instagram_search", "bilibili_search" -> true
        else -> false
    }

    /** Short friendly reply without calling the cloud AI (works offline). */
    private fun offlineToolReply(toolName: String, result: ToolResult): String {
        val data = result.data
        return when (toolName) {
            "app_launcher", "bale", "eitaa", "rubika", "whatsapp", "telegram", "instagram_app" ->
                "انجام شد. برنامه باز شد."
            "phone_call" -> "در حال برقراری تماس..."
            "sms" -> "پیامک آماده ارسال است."
            "accessibility_control" -> {
                when (data) {
                    is Map<*, *> -> data["text"]?.toString()?.take(200)
                        ?: data["message"]?.toString()
                        ?: "دستور صفحه انجام شد."
                    is String -> data.take(200)
                    else -> "دستور صفحه انجام شد."
                }
            }
            else -> {
                when (data) {
                    is Map<*, *> -> data["message"]?.toString() ?: "انجام شد."
                    is String -> data.take(200)
                    else -> "انجام شد."
                }
            }
        }
    }

    private fun detectToolIntent(input: String): ToolIntent? {
        val lower = input.lowercase()

        return when {
            containsAny(lower, "تماس", "زنگ بزن", "call") -> {
                val number = extractPhoneNumber(input)
                if (number != null) {
                    ToolIntent("phone_call", mapOf("phone_number" to number))
                } else null
            }

            containsAny(lower, "پیامک", "اس ام اس", "sms", "send message") -> {
                ToolIntent("sms", mapOf("message" to input))
            }

            containsAny(lower, "باز کن", "اجرا کن", "برنامه", "اپ", "open app", "launch") -> {
                val appName = extractAppName(input)
                if (appName != null) {
                    ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to appName))
                } else {
                    ToolIntent("app_launcher", mapOf("action" to "list"))
                }
            }

            containsAny(lower, "صفحه", "محتوا", "بخون", "چی نوشته", "screen", "read screen") -> {
                ToolIntent("accessibility_control", mapOf("action" to "read"))
            }

            containsAny(lower, "کلیک", "بزن", "فشار بده", "click", "tap") -> {
                val text = extractTargetText(input)
                if (text != null) {
                    ToolIntent("accessibility_control", mapOf("action" to "click", "text" to text))
                } else null
            }

            containsAny(lower, "تایپ کن", "بنویس", "وارد کن", "type", "enter") -> {
                val text = extractTargetText(input)
                if (text != null) {
                    ToolIntent("accessibility_control", mapOf("action" to "type", "text" to text))
                } else null
            }

            containsAny(lower, "اسکرول", "بالا", "پایین", "scroll") -> {
                val direction = if (containsAny(lower, "بالا", "up")) "up" else "down"
                ToolIntent("accessibility_control", mapOf("action" to "scroll", "direction" to direction))
            }

            containsAny(lower, "بازگشت", "برگرد", "back") -> {
                ToolIntent("accessibility_control", mapOf("action" to "back"))
            }

            containsAny(lower, "بیل", "bale") -> ToolIntent("bale", mapOf("action" to "open"))
            containsAny(lower, "ایتا", "eitaa") -> ToolIntent("eitaa", mapOf("action" to "open"))
            containsAny(lower, "روبیکا", "rubika") -> ToolIntent("rubika", mapOf("action" to "open"))
            containsAny(lower, "واتساپ", "واتس اپ", "whatsapp") -> ToolIntent("whatsapp", mapOf("action" to "open"))
            containsAny(lower, "تلگرام", "telegram") -> ToolIntent("telegram", mapOf("action" to "open"))
            containsAny(lower, "اینستاگرام", "اینستا", "instagram") ->
                ToolIntent("instagram_app", mapOf("action" to "open"))

            containsAny(lower, "جستجو", "search", "بگرد", "پیدا کن", "find") -> {
                when {
                    containsAny(lower, "github", "گیت‌هاب", "repository") ->
                        ToolIntent("github_search", mapOf("action" to "search", "query" to extractQuery(input)))
                    containsAny(lower, "youtube", "یوتیوب", "video", "ویدیو") ->
                        ToolIntent("youtube_search", mapOf("action" to "search", "query" to extractQuery(input)))
                    containsAny(lower, "reddit", "ردیت") ->
                        ToolIntent("reddit_search", mapOf("query" to extractQuery(input)))
                    else ->
                        ToolIntent("web_search", mapOf("query" to extractQuery(input)), useRouter = true)
                }
            }

            containsAny(lower, "باز کن", "open", "browse", "بخون", "read", "url", "http") -> {
                val url = extractUrl(input)
                if (url != null) ToolIntent("web_browse", mapOf("url" to url, "mode" to "text")) else null
            }

            containsAny(lower, "rss", "feed", "خبرخوان") -> {
                val url = extractUrl(input)
                if (url != null) ToolIntent("rss_reader", mapOf("url" to url)) else null
            }

            else -> null
        }
    }

    private fun extractQuery(input: String): String =
        input.replace(Regex("(جستجو|search|بگرد|پیدا کن|find|برای|for|about|درباره)", RegexOption.IGNORE_CASE), "").trim()

    private fun extractUrl(input: String): String? =
        Regex("https?://[^\\s]+").find(input)?.value

    private fun extractPhoneNumber(input: String): String? =
        Regex("\\d{10,11}").find(input)?.value

    private fun extractAppName(input: String): String? =
        input.replace(Regex("(باز کن|اجرا کن|برنامه|اپ|open|launch|app)", RegexOption.IGNORE_CASE), "")
            .trim().takeIf { it.isNotEmpty() }

    private fun extractTargetText(input: String): String? =
        input.replace(Regex("(کلیک|بزن|فشار بده|تایپ کن|بنویس|وارد کن|click|tap|type|enter|روی|on)", RegexOption.IGNORE_CASE), "")
            .trim().takeIf { it.isNotEmpty() }

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }

    private suspend fun generateResponseWithToolData(userInput: String, toolResult: ToolResult): String {
        val prompt = buildString {
            append("کاربر پرسیده: $userInput\n\n")
            append("من این اطلاعات رو براش پیدا کردم:\n")
            append(formatToolResult(toolResult))
            append("\n\nلطفاً یک پاسخ کوتاه و دوستانه به فارسی بده که این اطلاعات رو خلاصه کنه.")
        }
        val chatResponse = unoRouter.chat(prompt, emptyList())
        lastUsedModel = chatResponse.usedModel
        return chatResponse.text
    }

    private fun formatToolResult(result: ToolResult): String {
        return when (val data = result.data) {
            is List<*> -> data.take(5).mapIndexed { i, item -> "${i + 1}. $item" }.joinToString("\n")
            is Map<*, *> -> data.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            else -> data.toString()
        }
    }

    fun getLastUsedModel(): String? = lastUsedModel

    suspend fun getAvailableModels(): List<String> =
        unoRouter.fetchAvailableModels().map { it.id }

    suspend fun runHealthCheck(): String =
        toolManager.runDoctorCheck().toReadableString()

    fun listTools(): String {
        val tools = toolManager.listAllTools()
        return buildString {
            appendLine("🛠 ابزارهای موجود آریا:")
            tools.groupBy { it["category"] as String }.forEach { (category, categoryTools) ->
                appendLine("📁 $category:")
                categoryTools.forEach { tool ->
                    appendLine("  • ${tool["name"]}: ${tool["description"]}")
                }
                appendLine()
            }
        }
    }

    companion object {
        private const val TAG = "AgentBridge"
    }
}

data class ToolIntent(
    val toolName: String,
    val params: Map<String, Any>,
    val useRouter: Boolean = false
)

data class AgentResponse(
    val text: String,
    val usedTool: String? = null,
    val toolResult: ToolResult? = null
)
