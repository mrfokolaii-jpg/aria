package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import com.mrfokolaii.aria.agent.tools.ToolResult
import com.mrfokolaii.aria.memory.LocalKnowledgeStore
import com.mrfokolaii.aria.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bridge: local tools + offline LLM + knowledge + internet search + MCP. */
class AgentBridge(
    private val context: Context,
    apiKey: String,
    endpoint: String,
    model: String
) {
    private val toolManager = AgentToolManager(context)
    private val offlineLlm = OfflineLlmEngine(context)
    private val knowledgeStore = LocalKnowledgeStore(context)

    fun requestStop() {
        offlineLlm.requestStop()
    }

    fun clearStop() {
        offlineLlm.clearStop()
    }

    suspend fun processWithAgentTools(
        userInput: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onStatus: (String) -> Unit = {}
    ): AgentResponse = withContext(Dispatchers.IO) {

        offlineLlm.clearStop()

        val online = NetworkUtils.isOnline(context)
        val lower = userInput.lowercase().replace("\u200c", "")

        if (online) {
            try {
                val n = toolManager.ensureMcpToolsLoaded()
                if (n > 0) Log.d(TAG, "MCP tools ready: $n")
            } catch (e: Exception) {
                Log.w(TAG, "MCP load: ${e.message}")
            }
        }

        if (containsAny(lower, "لیست mcp", "ابزار mcp", "mcp tools", "لیست ام سی پی")) {
            val list = toolManager.listMcpTools()
            val msg = if (list.isEmpty()) {
                "ابزار MCP فعال نیست.\nدر SharedPreferences با نام aria بنویس:\nmcp_enabled=true\nmcp_server_url=آدرس سرور MCP"
            } else {
                "ابزارهای MCP:\n" + list.joinToString("\n") { "• $it" }
            }
            return@withContext AgentResponse(text = msg)
        }

        if (containsAny(lower, "لیست دانش", "فایل دانش", "دانش من", "بروزرسانی دانش")) {
            return@withContext AgentResponse(text = knowledgeStore.statusText())
        }

        if (isCapabilityQuestion(lower)) {
            try { onStatus("💬 پاسخ سریع…") } catch (_: Exception) {}
            return@withContext AgentResponse(text = capabilityAnswer(lower))
        }

        val toolIntent = detectToolIntent(userInput)

        if (toolIntent != null) {
            Log.d(TAG, "Tool intent: ${toolIntent.toolName} online=$online")

            if (!online && isOnlineOnlyTool(toolIntent.toolName)) {
                return@withContext AgentResponse(
                    text = "این کار به اینترنت نیاز دارد. بدون نت می‌تونم برنامه باز کنم، تماس بگیرم، پیامک بفرستم و از دانش محلی جواب بدهم."
                )
            }

            val statusMsg = when {
                toolIntent.toolName.startsWith("mcp_") -> "🔌 در حال اجرای ابزار MCP…"
                toolIntent.toolName == "web_search" -> "🔍 در حال جستجو در اینترنت…"
                toolIntent.toolName == "github_search" -> "🐙 در حال جستجو در گیت‌هاب…"
                toolIntent.toolName == "web_browse" -> "🌐 در حال باز کردن سایت…"
                toolIntent.toolName == "file_download" -> "⬇️ در حال دانلود…"
                toolIntent.toolName == "phone_call" -> "📞 در حال آماده‌سازی تماس…"
                toolIntent.toolName == "sms" -> "💬 در حال آماده‌سازی پیامک…"
                toolIntent.toolName == "app_launcher" -> "📱 در حال باز کردن برنامه…"
                else -> "⚙️ در حال اجرای ابزار…"
            }
            try { onStatus(statusMsg) } catch (_: Exception) {}

            val toolResult = if (toolIntent.useRouter) {
                toolManager.routeRequest(userInput, toolIntent.params)
            } else {
                toolManager.executeToolByName(toolIntent.toolName, toolIntent.params)
            }

            return@withContext if (toolResult.success) {
                val reply = when {
                    toolIntent.toolName.startsWith("mcp_") -> {
                        val raw = formatToolResult(toolResult)
                        "🔌 MCP (${toolIntent.toolName}):\n$raw"
                    }
                    toolIntent.toolName in listOf("web_browse", "app_launcher", "phone_call", "sms", "file_download") -> {
                        val raw = offlineToolReply(toolIntent.toolName, toolResult)
                        val prefix = when (toolIntent.toolName) {
                            "phone_call" -> "📞 "
                            "sms" -> "💬 "
                            "app_launcher" -> "📱 "
                            else -> ""
                        }
                        prefix + raw
                    }
                    toolIntent.toolName == "github_search" -> {
                        val raw = formatToolResult(toolResult)
                        try { onStatus("🧠 نتایج گیت‌هاب آماده — در حال فکر کردن…") } catch (_: Exception) {}
                        val summary = try {
                            offlineLlm.chat(
                                "این نتایج گیت‌هاب را در ۲-۳ جمله فارسی خلاصه کن و بهترین مخزن را پیشنهاد بده:\n$raw",
                                emptyList()
                            )
                        } catch (_: Exception) { "" }
                        buildString {
                            appendLine("🐙 نتایج جستجوی گیت‌هاب:")
                            appendLine(raw.ifBlank { "(نتیجه‌ای نبود)" })
                            if (summary.isNotBlank() && summary != "متوقف شد.") {
                                appendLine()
                                append("💡 ").append(summary.take(400))
                            }
                        }
                    }
                    toolIntent.toolName == "web_search" -> {
                        val raw = offlineToolReply("web_search", toolResult)
                        try { onStatus("🧠 نتایج جستجو آماده — در حال فکر کردن…") } catch (_: Exception) {}
                        val summary = try {
                            offlineLlm.chat(
                                "این نتایج جستجو را کوتاه فارسی جواب بده:\n$raw\n\nسؤال: $userInput",
                                emptyList()
                            )
                        } catch (_: Exception) { "" }
                        buildString {
                            appendLine("🔍 نتایج جستجوی اینترنت:")
                            appendLine(raw.ifBlank { "(نتیجه‌ای نبود)" })
                            if (summary.isNotBlank() && summary != "متوقف شد.") {
                                appendLine()
                                append("💡 ").append(summary.take(400))
                            }
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

        val text = offlineLlm.chat(userInput, conversationHistory)
        AgentResponse(text = text)
    }

    private fun isOnlineOnlyTool(name: String): Boolean {
        if (name.startsWith("mcp_")) return true
        return when (name) {
            "web_search", "file_download", "github_search", "youtube_search",
            "reddit_search", "rss_reader", "instagram_search", "bilibili_search" -> true
            else -> false
        }
    }

    private fun formatToolResult(toolResult: ToolResult): String {
        val data = toolResult.data
        val raw = when (data) {
            is List<*> -> data.take(4).joinToString("\n") { item ->
                when (item) {
                    is Map<*, *> -> {
                        val title = item["title"] ?: item["name"] ?: ""
                        val url = item["url"] ?: ""
                        val snip = item["snippet"] ?: item["description"] ?: item["text"] ?: ""
                        "• $title\n  $url\n  $snip"
                    }
                    else -> item.toString()
                }
            }
            is Map<*, *> -> {
                val mcpText = data["text"] ?: data["message"]
                if (mcpText != null) return htmlDecode(mcpText.toString())
                val results = data["results"]
                if (results is List<*>) {
                    results.take(4).joinToString("\n") { item ->
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
        return htmlDecode(raw)
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

        if (containsAny(lower, "گودو", "godot", "موتور بازی", "unity", "unreal", "flutter", "react native")) {
            val q = extractQuery(input).ifBlank { input.trim() }
            return ToolIntent("github_search", mapOf("action" to "search", "query" to q))
        }

        if (containsAny(lower, "تماس", "زنگ بزن", "شماره بگیر", "مخاطب", "شماره‌گیر", "شماره گیر")) {
            if (containsAny(lower, "مخاطبین", "لیست مخاطب", "دفترچه")) {
                return ToolIntent("phone_call", mapOf("action" to "contacts"), useRouter = false)
            }
            if (containsAny(lower, "شماره‌گیر", "شماره گیر", "dialer")) {
                return ToolIntent("phone_call", mapOf("action" to "dialer"), useRouter = false)
            }
            val name = input
                .replace(Regex("(لطفا|خواهشا)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("(تماس( بگیر)?|زنگ بزن( به)?|شماره بگیر|با|به|از)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (name.isNotBlank()) {
                return ToolIntent(
                    "phone_call",
                    mapOf("action" to "call_name", "contact_name" to name),
                    useRouter = false
                )
            }
            return ToolIntent("phone_call", mapOf("action" to "contacts"), useRouter = false)
        }

        if (containsAny(lower, "پیامک", "اس ام اس", "اس‌ام‌اس", "sms", "پیام بفرست", "متن بفرست")) {
            var body = ""
            val bodyMatch = Regex("[:：]\\s*(.+)\$").find(input)
            if (bodyMatch != null) body = bodyMatch.groupValues[1].trim()
            val name = input
                .replace(Regex("[:：].*\$"), " ")
                .replace(Regex("(لطفا|خواهشا)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("(پیامک|اس.?ام.?اس|sms|پیام بفرست|متن بفرست|بفرست|به|برای)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val params = mutableMapOf<String, Any>("action" to "compose")
            if (name.isNotBlank()) params["contact_name"] = name
            if (body.isNotBlank()) params["message"] = body
            return ToolIntent("sms", params, useRouter = false)
        }

        if (containsAny(lower, "باز کن", "بازکن", "اجرا کن", "بازش کن")) {
            val appName = input
                .replace(Regex("(لطفا|خواهشا)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("(را|رو)?\\s*(باز\\s*کن|بازکن|اجرا کن|بازش کن)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("(اپ|برنامه|اپلیکیشن)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (appName.isNotBlank() && !containsAny(appName.lowercase(), "سایت", "گیتهاب", "گیت‌هاب", "github")) {
                return ToolIntent(
                    "app_launcher",
                    mapOf("action" to "launch", "app_name" to appName),
                    useRouter = false
                )
            }
        }

        val mcpMatch = Regex("mcp_[a-zA-Z0-9_\\-]+").find(lower)
        if (mcpMatch != null) {
            return ToolIntent(mcpMatch.value, mapOf("query" to extractQuery(input)), useRouter = false)
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

    private fun isCapabilityQuestion(lower: String): Boolean {
        val ask = containsAny(lower, "میتونی", "می‌تونی", "می تونی", "می‌توانی", "میتوانی", "بلدی", "داری")
        val topic = containsAny(
            lower, "صحبت", "حرف بزن", "صدا", "ویس", "جستجو", "سرچ", "اینترنت",
            "گیتهاب", "گیت‌هاب", "github", "تماس", "پیامک", "اپ", "برنامه", "mcp"
        )
        return ask && topic
    }

    private fun capabilityAnswer(lower: String): String {
        return when {
            containsAny(lower, "صحبت", "حرف بزن", "صدا", "ویس") ->
                "بله، با صدای بلند جواب می‌دم. بگو چی کار برات انجام بدم."
            containsAny(lower, "mcp") ->
                "اگر سرور MCP را در تنظیمات فعال کرده باشی، ابزارهایش را لود می‌کنم. بگو «لیست mcp»."
            containsAny(lower, "گیتهاب", "گیت‌هاب", "github") &&
                containsAny(lower, "اینترنت", "جستجو", "سرچ") ->
                "بله، هم اینترنت و هم گیت‌هاب را سرچ می‌کنم. موضوع را بگو تا همان را پیدا کنم."
            containsAny(lower, "گیتهاب", "گیت‌هاب", "github") ->
                "بله، گیت‌هاب را سرچ می‌کنم. نام پروژه یا موضوع را بگو."
            containsAny(lower, "اینترنت", "جستجو", "سرچ") ->
                "بله، اینترنت را سرچ می‌کنم. بگو دنبال چی بگردم."
            containsAny(lower, "تماس") ->
                "بله، می‌تونم مخاطب را پیدا کنم و تماس بگیرم. اسم را بگو."
            containsAny(lower, "پیامک") ->
                "بله، می‌تونم پیامک آماده کنم. بگو برای کی و چه متنی."
            else ->
                "بله، سرچ اینترنت و گیت‌هاب، تماس، پیامک، باز کردن اپ، MCP و پاسخ صوتی را انجام می‌دم. دستورت را بگو."
        }
    }

    private fun htmlDecode(s: String): String {
        val e = Char(38).toString()
        var t = s
        t = t.replace(e + "a" + "mp;", e)
        t = t.replace(e + "l" + "t;", "<")
        t = t.replace(e + "g" + "t;", ">")
        t = t.replace(e + "q" + "uot;", "\"")
        t = t.replace(e + "#39;", "'")
        t = t.replace(e + "n" + "bsp;", " ")
        return t
    }

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
