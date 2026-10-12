package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.tools.ToolResult
import com.mrfokolaii.aria.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Response from AgentBridge processWithAgentTools */
data class AgentResponse(
    val text: String,
    val toolUsed: String? = null,
    val toolResult: ToolResult? = null
)

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
                AgentResponse(text = reply, toolUsed = toolIntent.toolName, toolResult = toolResult)
            } else {
                AgentResponse(
                    text = toolResult.error ?: "انجام نشد. دوباره بگو."
                )
            }
        }

        if (!online) {
            return@withContext AgentResponse(
                text = "متوجه نشدم. می‌تونی بگی مثلاً: روبیکا رو باز کن، به علی زنگ بزن، پیامک بفرست، یا کروم رو باز کن."
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

    private fun offlineToolReply(toolName: String, result: ToolResult): String {
        val data = result.data
        return when (toolName) {
            "app_launcher", "bale", "eitaa", "rubika", "whatsapp", "telegram", "instagram_app" -> {
                val map = data as? Map<*, *>
                map?.get("message")?.toString() ?: "برنامه باز شد."
            }
            "phone_call" -> {
                val map = data as? Map<*, *>
                map?.get("message")?.toString()
                    ?: when (map?.get("status")?.toString()) {
                        "contacts_opened" -> "لیست مخاطبین باز شد."
                        "dialer_opened" -> "شماره‌گیر باز شد."
                        "not_found" -> "مخاطب پیدا نشد."
                        else -> "تماس آماده شد."
                    }
            }
            "sms" -> {
                val map = data as? Map<*, *>
                map?.get("message")?.toString() ?: "پیامک باز شد."
            }
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
        val lower = input.lowercase().replace("‌", "")

        val callName = Regex(
            "(?:زنگ\\s*بزن|تماس\\s*بگیر|تماس\\s*بگیره|تماس\\s*بگیرم|call)\\s*(?:به|با)?\\s*(.+)",
            RegexOption.IGNORE_CASE
        ).find(input)
        if (callName != null) {
            val name = callName.groupValues[1]
                .replace(Regex("(لطفا|کن|بکن|بگیر|بزن)"), "")
                .trim()
            if (name.isNotBlank() && extractPhoneNumber(name) == null) {
                return ToolIntent("phone_call", mapOf("action" to "search", "contact_name" to name))
            }
        }

        if (containsAny(lower, "تماس‌ها", "تماس ها", "لیست تماس", "دفترچه", "مخاطبین", "contacts")) {
            return ToolIntent("phone_call", mapOf("action" to "contacts"))
        }

        if (containsAny(lower, "تماس", "زنگ بزن", "شماره‌گیر", "شماره گیر", "call", "dial")) {
            val number = extractPhoneNumber(input)
            return if (number != null) {
                ToolIntent("phone_call", mapOf("action" to "call", "phone_number" to number))
            } else {
                val name = input.replace(Regex("(تماس|زنگ\\s*بزن|بگیر|با|به|کن|لطفا|call|dial)", RegexOption.IGNORE_CASE), "").trim()
                if (name.length in 2..40) {
                    ToolIntent("phone_call", mapOf("action" to "search", "contact_name" to name))
                } else {
                    ToolIntent("phone_call", mapOf("action" to "dialer"))
                }
            }
        }

        if (containsAny(lower, "پیامک", "اس ام اس", "اس‌ام‌اس", "sms", "message")) {
            val number = extractPhoneNumber(input)
            val nameMatch = Regex("(?:پیامک|اس\\s*ام\\s*اس|sms)\\s*(?:به|برای)?\\s*([^\\s]+)", RegexOption.IGNORE_CASE).find(input)
            val contact = nameMatch?.groupValues?.getOrNull(1)?.takeIf {
                it.length > 1 && extractPhoneNumber(it) == null &&
                    !it.contains("بفرست") && !it.equals("باز", true)
            }
            val body = input
                .replace(Regex("(پیامک|اس\\s*ام\\s*اس|sms|بفرست|به|برای)", RegexOption.IGNORE_CASE), " ")
                .let { if (contact != null) it.replace(contact, " ") else it }
                .replace(Regex("\\d{8,}"), " ")
                .trim()
                .takeIf { it.length > 1 }
            val params = mutableMapOf<String, Any>()
            if (number != null) params["phone_number"] = number
            if (contact != null) params["contact_name"] = contact
            if (body != null) params["message"] = body
            return ToolIntent("sms", params)
        }

        when {
            containsAny(lower, "روبیکا", "rubika") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "روبیکا"))
            containsAny(lower, "بله", "bale") && !lower.contains("بلیت") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "بله"))
            containsAny(lower, "ایتا", "eitaa", "eita") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "ایتا"))
            containsAny(lower, "واتساپ", "واتس اپ", "واتس‌اپ", "whatsapp") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "واتساپ"))
            containsAny(lower, "تلگرام", "telegram") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "تلگرام"))
            containsAny(lower, "اینستاگرام", "اینستا", "instagram") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "اینستاگرام"))
            containsAny(lower, "کروم", "chrome", "گوگل کروم") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "کروم"))
            containsAny(lower, "فایرفاکس", "firefox", "فایر فاکس") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "فایرفاکس"))
            containsAny(lower, "vpn", "ویپیان", "وی پی ان", "وی‌پی‌ان") ->
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "vpn"))
        }

        if (containsAny(lower, "باز کن", "اجرا کن", "بازکن", "open", "launch") ||
            (containsAny(lower, "برنامه", "اپ") && containsAny(lower, "باز", "اجرا"))
        ) {
            val appName = extractAppName(input)
            if (!appName.isNullOrBlank()) {
                return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to appName))
            }
        }

        if (containsAny(lower, "چه برنامه‌", "لیست برنامه", "برنامه‌های نصب", "apps list", "list apps")) {
            return ToolIntent("app_launcher", mapOf("action" to "list"))
        }

        if (containsAny(lower, "بخوان", "بخون", "صفحه چی", "read screen")) {
            return ToolIntent("accessibility_control", mapOf("action" to "read_screen"))
        }

        if (containsAny(lower, "کلیک", "بزن روی", "فشار بده", "click", "tap")) {
            val target = extractTargetText(input)
            if (!target.isNullOrBlank()) {
                return ToolIntent("accessibility_control", mapOf("action" to "click", "text" to target))
            }
        }

        if (containsAny(lower, "تایپ", "بنویس", "type", "enter text")) {
            val target = extractTargetText(input)
            if (!target.isNullOrBlank()) {
                return ToolIntent("accessibility_control", mapOf("action" to "type", "text" to target))
            }
        }

        if (containsAny(lower, "اسکرول", "ورق بزن", "scroll")) {
            val direction = if (containsAny(lower, "بالا", "up")) "up" else "down"
            return ToolIntent("accessibility_control", mapOf("action" to "scroll", "direction" to direction))
        }

        if (containsAny(lower, "بازگشت", "برگرد", "back")) {
            return ToolIntent("accessibility_control", mapOf("action" to "back"))
        }

        if (containsAny(lower, "جستجو", "سرچ", "search", "google")) {
            return ToolIntent("web_search", mapOf("query" to extractQuery(input)), useRouter = true)
        }

        if (containsAny(lower, "سایت", "browse", "http")) {
            val url = extractUrl(input)
            if (url != null) return ToolIntent("web_browse", mapOf("url" to url), useRouter = true)
        }

        return null
    }

    private fun extractQuery(input: String): String =
        input.replace(Regex("(جستجو|سرچ|search|google)\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun extractUrl(input: String): String? =
        Regex("https?://\\S+").find(input)?.value

    private fun extractPhoneNumber(input: String): String? =
        Regex("0?9\\d{9}|\\+?\\d{10,13}").find(input.replace(" ", ""))?.value

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
        return tools.joinToString("\n") { t ->
            "- ${t["name"]}: ${t["description"]}"
        }
    }

    private data class ToolIntent(
        val toolName: String,
        val params: Map<String, Any> = emptyMap(),
        val useRouter: Boolean = false
    )

    companion object {
        private const val TAG = "AgentBridge"
    }
}
