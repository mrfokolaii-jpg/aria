package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.tools.ToolResult
import com.mrfokolaii.aria.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentBridge(
    private val context: Context,
    apiKey: String,
    endpoint: String,
    model: String
) {
    private val toolManager = AgentToolManager(context)
    private val unoRouter = UnoRouterProvider(apiKey, endpoint, model)
    private var lastUsedModel: String? = model

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

        // Free chat — always try cloud (network probe is unreliable on VPN/Xiaomi)
        val chatResponse = unoRouter.chat(userInput, conversationHistory)
        lastUsedModel = chatResponse.usedModel
        AgentResponse(text = chatResponse.text)
    }

    private fun isOnlineOnlyTool(name: String): Boolean = when (name) {
        "web_search", "web_browse", "github_search", "youtube_search",
        "reddit_search", "rss_reader", "instagram_search", "bilibili_search" -> true
        else -> false
    }

    private fun offlineToolReply(toolName: String, result: ToolResult): String {
        val data = result.data
        return when (toolName) {
            "app_launcher", "bale", "eitaa", "rubika", "whatsapp", "telegram", "instagram_app" ->
                "انجام شد. برنامه باز شد."
            "phone_call" -> {
                val status = (data as? Map<*, *>)?.get("status")?.toString()
                when (status) {
                    "contacts_opened" -> "لیست مخاطبین باز شد."
                    "dialer_opened" -> "شماره‌گیر باز شد."
                    else -> "در حال برقراری تماس..."
                }
            }
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
            containsAny(lower, "تماس‌ها", "لیست تماس", "دفترچه", "contacts") -> {
                ToolIntent("phone_call", mapOf("action" to "contacts"))
            }

            containsAny(lower, "تماس", "زنگ بزن", "شماره‌گیر", "call", "dial") -> {
                val number = extractPhoneNumber(input)
                if (number != null) {
                    ToolIntent("phone_call", mapOf("action" to "call", "phone_number" to number))
                } else {
                    ToolIntent("phone_call", mapOf("action" to "dialer"))
                }
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

            containsAny(lower, "جستجو", "سرچ", "search", "google") -> {
                ToolIntent("web_search", mapOf("query" to extractQuery(input)), useRouter = true)
            }

            containsAny(lower, "سایت", "باز کن سایت", "browse", "http") -> {
                val url = extractUrl(input)
                if (url != null) ToolIntent("web_browse", mapOf("url" to url), useRouter = true)
                else null
            }

            else -> null
        }
    }

    private fun extractQuery(input: String): String =
        input.replace(Regex("(جستجو|سرچ|search|google)\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun extractUrl(input: String): String? =
        Regex("https?://\\S+").find(input)?.value

    private fun extractPhoneNumber(input: String): String? =
        Regex("0?9\\d{9}|\\+?\\d{10,13}").find(input.replace(" ", ""))?.value

    private fun extractAppName(input: String): String? {
        val cleaned = input.replace(Regex("(باز کن|اجرا کن|برنامه|اپ|open|launch)", RegexOption.IGNORE_CASE), "").trim()
        return cleaned.takeIf { it.isNotBlank() && it.length < 40 }
    }

    private fun extractTargetText(input: String): String? {
        val cleaned = input.replace(Regex("(کلیک|بزن|فشار بده|تایپ کن|بنویس|وارد کن|click|tap|type)", RegexOption.IGNORE_CASE), "").trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it) }

    private suspend fun generateResponseWithToolData(userInput: String, toolResult: ToolResult): String {
        val summary = formatToolResult(toolResult)
        val prompt = "کاربر گفت: $userInput\nنتیجه ابزار: $summary\nیک پاسخ کوتاه فارسی بده."
        return unoRouter.chat(prompt).text
    }

    private fun formatToolResult(result: ToolResult): String {
        return when (val d = result.data) {
            is Map<*, *> -> d.entries.joinToString(", ") { "${it.key}=${it.value}" }
            is String -> d
            null -> result.error ?: "ok"
            else -> d.toString()
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

    data class AgentResponse(
        val text: String,
        val usedTool: String? = null,
        val toolResult: ToolResult? = null
    )

    private data class ToolIntent(
        val toolName: String,
        val params: Map<String, Any> = emptyMap(),
        val useRouter: Boolean = false
    )

    companion object {
        private const val TAG = "AgentBridge"
    }
}
