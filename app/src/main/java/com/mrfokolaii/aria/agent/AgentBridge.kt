package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.tools.ToolResult
import com.mrfokolaii.aria.memory.LocalKnowledgeStore
import com.mrfokolaii.aria.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AgentResponse(
    val text: String,
    val toolUsed: String? = null,
    val toolResult: ToolResult? = null
)

/** Bridge: local tools + offline Qwen + local knowledge + internet search/download. */
class AgentBridge(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") apiKey: String = "",
    @Suppress("UNUSED_PARAMETER") endpoint: String = "",
    @Suppress("UNUSED_PARAMETER") model: String = ""
) {

    private val toolManager = AgentToolManager(context)
    private val offlineLlm = OfflineLlmEngine(context)
    private val knowledgeStore = LocalKnowledgeStore(context)
    private var lastUsedModel: String? = "offline"

    fun setAccessibilityService(service: android.accessibilityservice.AccessibilityService?) {
        toolManager.setAccessibilityService(service)
    }

    suspend fun processWithAgentTools(
        userInput: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): AgentResponse = withContext(Dispatchers.IO) {

        val online = NetworkUtils.isOnline(context)
        val lower = userInput.lowercase().replace("‌", "")

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
                            offlineLlm.chat(
                                "نتایج جستجو:\n$raw\n\nسؤال: $userInput\nخیلی کوتاه فارسی جواب بده.",
                                emptyList()
                            )
                        } catch (_: Exception) {
                            raw.take(1200)
                        }
                    }
                    else -> try {
                        generateResponseWithToolData(userInput, toolResult)
                    } catch (_: Exception) {
                        offlineToolReply(toolIntent.toolName, toolResult)
                    }
                }
                AgentResponse(text = reply, toolUsed = toolIntent.toolName, toolResult = toolResult)
            } else {
                AgentResponse(text = toolResult.error ?: "انجام نشد. دوباره بگو.")
            }
        }

        val knowledge = try { knowledgeStore.retrieveRelevant(userInput) } catch (_: Exception) { "" }
        val prompt = if (knowledge.isNotBlank()) {
            "دانش مرتبط:\n$knowledge\n\nسؤال: $userInput\nکوتاه جواب بده."
        } else userInput
        val text = offlineLlm.chat(prompt, conversationHistory)
        lastUsedModel = "offline"
        AgentResponse(text = text)
    }

    private fun isOnlineOnlyTool(name: String): Boolean = when (name) {
        "web_search", "file_download", "github_search", "youtube_search",
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
            "web_browse" -> {
                val map = data as? Map<*, *>
                map?.get("message")?.toString() ?: "سایت در مرورگر باز شد."
            }
            "file_download" -> {
                val map = data as? Map<*, *>
                map?.get("message")?.toString() ?: "دانلود انجام شد."
            }
            "web_search" -> {
                when (data) {
                    is List<*> -> data.take(5).joinToString("\n") { "• $it" }
                    is Map<*, *> -> data["message"]?.toString() ?: data.toString()
                    else -> data?.toString() ?: "نتیجه جستجو"
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

        // GitHub search stays inside Aria (API) — only pure "open site" opens browser
        if (containsAny(lower, "github", "گیتهاب", "گیت‌هاب", "گیتهب", "گیت هاب")) {
            val onlyOpen = containsAny(lower, "باز کن", "بازکن", "برو به سایت") &&
                !containsAny(lower, "سرچ", "جستجو", "search", "بگرد", "پیدا")
            if (!onlyOpen) {
                val q = input
                    .replace(Regex("(گیت.?هاب|گیتهاب|github|سرچ|جستجو|بگرد|کن|لطفا|search)", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { "android assistant" }
                return ToolIntent("github_search", mapOf("action" to "search", "query" to q))
            }
        }

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

        if (containsAny(
                lower,
                "پیامک", "اس ام اس", "اس‌ام‌اس", "اساماس", "sms", "message",
                "پیام رسان", "پیام‌رسان", "پیامرسان", "messages", "messaging"
            )
        ) {
            val number = extractPhoneNumber(input)
            val nameMatch = Regex(
                "(?:پیامک|اس\\s*ام\\s*اس|پیام\\s*رسان|sms)\\s*(?:به|برای)?\\s*([^\\s]+)",
                RegexOption.IGNORE_CASE
            ).find(input)
            val contact = nameMatch?.groupValues?.getOrNull(1)?.takeIf {
                val c = it.trim()
                c.length > 1 && extractPhoneNumber(c) == null &&
                    !containsAny(c, "بفرست", "باز", "کن", "گوشی", "گوشیو", "برنامه", "اپ")
            }
            val wantsBody = containsAny(lower, "بفرست", "بگو", "بنویس", "با متن")
            val body = if (wantsBody) {
                input
                    .replace(Regex("(پیامک|اس\\s*ام\\s*اس|پیام\\s*رسان|sms|بفرست|به|برای|باز\\s*کن)", RegexOption.IGNORE_CASE), " ")
                    .let { if (contact != null) it.replace(contact, " ") else it }
                    .replace(Regex("\\d{8,}"), " ")
                    .trim()
                    .takeIf { it.length > 1 }
            } else null
            val params = mutableMapOf<String, Any>()
            if (number != null) params["phone_number"] = number
            if (contact != null) params["contact_name"] = contact
            if (body != null) params["message"] = body
            return ToolIntent("sms", params)
        }

        if (containsAny(lower, "باز", "بیار", "برو", "سایت", "open", "browse")) {
            val siteUrl = resolveKnownSite(input)
            if (siteUrl != null) {
                return ToolIntent("web_browse", mapOf("action" to "open", "url" to siteUrl))
            }
        }

        if (containsAny(lower, "مرورگر", "browser", "براوزر")) {
            return ToolIntent("app_launcher", mapOf("action" to "launch", "app_name" to "مرورگر"))
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

        if (containsAny(lower, "دانلود", "download") && extractUrl(input) != null) {
            return ToolIntent("file_download", mapOf("url" to extractUrl(input)!!))
        }

        if (containsAny(lower, "جستجو", "سرچ", "search", "گوگل کن", "بگرد")) {
            return ToolIntent("web_search", mapOf("query" to extractQuery(input)), useRouter = false)
        }

        if (containsAny(lower, "سایت", "browse", "http")) {
            val url = extractUrl(input) ?: resolveKnownSite(input)
            if (url != null) {
                return ToolIntent("web_browse", mapOf("action" to "open", "url" to url))
            }
        }

        return null
    }

    private fun resolveKnownSite(input: String): String? {
        val lower = input.lowercase().replace("‌", "").replace(" ", "")
        return when {
            lower.contains("github") || lower.contains("گیتهاب") || lower.contains("گیت‌هاب")
                || lower.contains("گیتهب") || lower.contains("گیتا") -> "https://github.com"
            lower.contains("google") || lower.contains("گوگل") -> "https://www.google.com"
            lower.contains("youtube") || lower.contains("یوتیوب") || lower.contains("یوتوب") -> "https://www.youtube.com"
            lower.contains("openai") || lower.contains("chatgpt") -> "https://chatgpt.com"
            else -> null
        }
    }

    private fun extractQuery(input: String): String =
        input.replace(Regex("(جستجو|سرچ|search|google|گوگل\\s*کن|بگرد)\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun extractUrl(input: String): String? =
        Regex("https?://\\S+").find(input)?.value

    private fun extractPhoneNumber(input: String): String? =
        Regex("0?9\\d{9}|\\+?\\d{10,13}").find(input.replace(" ", ""))?.value

    private fun extractAppName(input: String): String? =
        input
            .replace(Regex("(باز\\s*کن|اجرا\\s*کن|بازکن|برنامه|اپلیکیشن|اپ|لطفا|open|launch|app)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('و', ' ')
            .takeIf { it.length >= 2 }

    private fun extractTargetText(input: String): String? =
        input.replace(Regex("(کلیک|بزن|فشار بده|تایپ کن|بنویس|وارد کن|click|tap|type|enter|روی|on)", RegexOption.IGNORE_CASE), "")
            .trim().takeIf { it.isNotEmpty() }

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }

    private suspend fun generateResponseWithToolData(userInput: String, toolResult: ToolResult): String {
        val prompt = buildString {
            append("کاربر: $userInput\nنتیجه ابزار:\n")
            append(formatToolResult(toolResult))
            append("\nکوتاه فارسی بگو.")
        }
        return offlineLlm.chat(prompt, emptyList())
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
        offlineLlm.listDetectedModels().ifEmpty { listOf("هیچ مدلی پیدا نشد — .task یا .gguf آپلود کن") }

    suspend fun runHealthCheck(): String =
        toolManager.runDoctorCheck().toReadableString() + "\n" +
            offlineLlm.modelStatusText() + "\n" + knowledgeStore.statusText()

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
