package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge between ARIA service and Agent Tools.
 * Handles tool detection, execution, and integration with AI.
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

    /**
     * Process user input with Agent capabilities.
     * Detects if input requires tool use and executes accordingly.
     */
    suspend fun processWithAgentTools(
        userInput: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): AgentResponse = withContext(Dispatchers.IO) {

        // Detect if user input requires tool use
        val toolIntent = detectToolIntent(userInput)

        if (toolIntent != null) {
            Log.d(TAG, "Tool intent detected: ${toolIntent.toolName}")

            // Execute tool
            val toolResult = if (toolIntent.useRouter) {
                toolManager.routeRequest(userInput, toolIntent.params)
            } else {
                toolManager.executeToolByName(toolIntent.toolName, toolIntent.params)
            }

            // Generate AI response based on tool result
            return@withContext if (toolResult.success) {
                val aiResponse = generateResponseWithToolData(userInput, toolResult)
                AgentResponse(
                    text = aiResponse,
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

        // No tool needed, use regular AI
        val chatResponse = unoRouter.chat(userInput, conversationHistory)
        lastUsedModel = chatResponse.usedModel
        AgentResponse(text = chatResponse.text)
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

            containsAny(lower, "بیل", "bale") -> {
                ToolIntent("bale", mapOf("action" to "open"))
            }

            containsAny(lower, "ایتا", "eitaa") -> {
                ToolIntent("eitaa", mapOf("action" to "open"))
            }

            containsAny(lower, "روبیکا", "rubika") -> {
                ToolIntent("rubika", mapOf("action" to "open"))
            }

            containsAny(lower, "واتساپ", "واتس اپ", "whatsapp") -> {
                ToolIntent("whatsapp", mapOf("action" to "open"))
            }

            containsAny(lower, "تلگرام", "telegram") -> {
                ToolIntent("telegram", mapOf("action" to "open"))
            }

            containsAny(lower, "اینستاگرام", "اینستا", "instagram") -> {
                ToolIntent("instagram_app", mapOf("action" to "open"))
            }

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
                if (url != null) {
                    ToolIntent("web_browse", mapOf("url" to url, "mode" to "text"))
                } else null
            }

            containsAny(lower, "rss", "feed", "خبرخوان") -> {
                val url = extractUrl(input)
                if (url != null) {
                    ToolIntent("rss_reader", mapOf("url" to url))
                } else null
            }

            else -> null
        }
    }

    private fun extractQuery(input: String): String {
        return input
            .replace(Regex("(جستجو|search|بگرد|پیدا کن|find|برای|for|about|درباره)", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun extractUrl(input: String): String? {
        val urlRegex = Regex("https?://[^\\s]+")
        return urlRegex.find(input)?.value
    }

    private fun extractPhoneNumber(input: String): String? {
        val phoneRegex = Regex("\\d{10,11}")
        return phoneRegex.find(input)?.value
    }

    private fun extractAppName(input: String): String? {
        return input
            .replace(Regex("(باز کن|اجرا کن|برنامه|اپ|open|launch|app)", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun extractTargetText(input: String): String? {
        return input
            .replace(Regex("(کلیک|بزن|فشار بده|تایپ کن|بنویس|وارد کن|click|tap|type|enter|روی|on)", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

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
            is List<*> -> {
                data.take(5).mapIndexed { index, item ->
                    "${index + 1}. $item"
                }.joinToString("\n")
            }
            is Map<*, *> -> {
                data.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            }
            else -> data.toString()
        }
    }

    fun getLastUsedModel(): String? = lastUsedModel

    suspend fun getAvailableModels(): List<String> {
        return unoRouter.fetchAvailableModels().map { it.id }
    }

    /**
     * Run health check on all tools
     */
    suspend fun runHealthCheck(): String {
        val report = toolManager.runDoctorCheck()
        return report.toReadableString()
    }

    /**
     * List all available tools
     */
    fun listTools(): String {
        val tools = toolManager.listAllTools()
        return buildString {
            appendLine("🛠 ابزارهای موجود آریا:")
            appendLine()
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
