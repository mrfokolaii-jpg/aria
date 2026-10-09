package com.mrfokolaii.aria.agent.tools

import android.util.Log

class ToolRouter(private val registry: ToolRegistry) {

    suspend fun route(userRequest: String, params: Map<String, Any> = emptyMap()): ToolResult {
        val normalizedRequest = userRequest.lowercase()

        val selectedTool = when {
            // Web search & browse
            containsAny(normalizedRequest, "search", "google", "find", "جستجو", "بگرد") &&
            !containsAny(normalizedRequest, "github", "youtube", "reddit", "twitter", "x.com") ->
                registry.getTool("web_search")

            containsAny(normalizedRequest, "open", "browse", "visit", "url", "website", "باز کن", "سایت") ->
                registry.getTool("web_browse")

            // GitHub
            containsAny(normalizedRequest, "github", "repository", "repo", "code", "گیت‌هاب") ->
                registry.getTool("github_search")

            // YouTube
            containsAny(normalizedRequest, "youtube", "video", "یوتیوب", "ویدیو") ->
                registry.getTool("youtube_search")

            // Social Media
            containsAny(normalizedRequest, "reddit", "ردیت") ->
                registry.getTool("reddit_search")

            // Instagram (kept active)
            containsAny(normalizedRequest, "instagram", "insta", "اینستاگرام") ->
                registry.getTool("instagram_search")

            // Chinese platforms
            containsAny(normalizedRequest, "bilibili", "بیلی‌بیلی") ->
                registry.getTool("bilibili_search")

            // Iranian apps
            containsAny(normalizedRequest, "بیل", "bale") ->
                registry.getTool("bale")

            containsAny(normalizedRequest, "ایتا", "eitaa") ->
                registry.getTool("eitaa")

            containsAny(normalizedRequest, "روبیکا", "rubika") ->
                registry.getTool("rubika")

            containsAny(normalizedRequest, "واتساپ", "whatsapp") ->
                registry.getTool("whatsapp")

            containsAny(normalizedRequest, "تلگرام", "telegram") ->
                registry.getTool("telegram")

            containsAny(normalizedRequest, "instagram app", "open instagram") ->
                registry.getTool("instagram_app")

            // RSS
            containsAny(normalizedRequest, "rss", "feed", "subscribe") ->
                registry.getTool("rss_reader")

            else -> null
        }

        if (selectedTool == null) {
            return ToolResult(
                success = false,
                data = null,
                error = "Could not determine appropriate tool for request: $userRequest"
            )
        }

        Log.d("ToolRouter", "Routing request to: ${selectedTool.name}")

        return try {
            selectedTool.execute(params)
        } catch (e: Exception) {
            Log.e("ToolRouter", "Tool execution failed", e)
            ToolResult(
                success = false,
                data = null,
                error = "Tool execution failed: ${e.message}"
            )
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }
}
