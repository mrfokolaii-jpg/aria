# ARIA Agent Tools - Developer Guide

## Overview

This document provides technical details about ARIA's Agent Tools system, inspired by Agent Reach architecture but adapted for Android.

## Architecture

### Core Components

```
AgentToolManager
    ↓
    ├─→ ToolRegistry (manages all tools)
    ├─→ ToolRouter (intelligent routing)
    └─→ Individual Tools
```

### Component Descriptions

#### 1. AgentTool Interface

Base interface that all tools must implement:

```kotlin
interface AgentTool {
    val name: String
    val description: String
    val category: ToolCategory
    
    suspend fun execute(params: Map<String, Any>): ToolResult
    suspend fun healthCheck(): HealthStatus
    fun getParamsSchema(): JSONObject
}
```

#### 2. ToolRegistry

Manages tool registration and health checking:

- Registers tools
- Retrieves tools by name or category
- Caches health status (5-minute TTL)
- Parallel health checks

#### 3. ToolRouter

Intelligently routes user requests to appropriate tools:

- Keyword-based detection (supports Persian & English)
- Automatic tool selection
- Fallback handling

#### 4. AgentBridge

Bridge between ARIA service and agent tools:

- Detects tool intent from user input
- Executes tools
- Generates AI responses with tool results
- Integrates with Gemini API

## Available Tools

### Web Tools

#### WebSearchTool
- **Primary**: DuckDuckGo HTML scraping
- **Fallback**: Brave Search API (requires API key)
- **Params**: `query`, `max_results`

#### WebBrowseTool
- Fetches and extracts content from URLs
- **Modes**: text, markdown, html
- **Params**: `url`, `mode`

### Code Repository Tools

#### GitHubTool
- **Actions**:
  - `search`: Search repositories
  - `get_file`: Read file content
  - `list_issues`: List issues
  - `get_readme`: Get README
- **Params**: `action`, `query`, `repo`, `path`, `branch`

### Media Tools

#### YouTubeTool
- **Actions**:
  - `search`: Search videos (scrapes HTML)
  - `get_transcript`: Get video transcript (placeholder)
- **Params**: `action`, `query`, `video_id`

### Social Media Tools

All extend `SocialMediaTool` base class:

#### RedditTool
- Uses Reddit JSON API
- **Params**: `query`, `subreddit`, `sort`

#### TwitterTool, FacebookTool, InstagramTool, LinkedInTool
- Placeholder implementations
- Require OAuth/API authentication

#### BilibiliTool
- Uses Bilibili public API
- **Params**: `query`

#### XiaohongshuTool
- Placeholder implementation
- Requires app-specific auth

### RSS Tools

#### RSSReaderTool
- Parses RSS 2.0 and Atom feeds
- Uses JSoup XML parser
- **Params**: `url`, `max_items`

## Tool Development Guide

### Step 1: Create Tool Class

```kotlin
class MyCustomTool(private val client: OkHttpClient) : AgentTool {
    override val name = "my_custom_tool"
    override val description = "Description of what this tool does"
    override val category = ToolCategory.WEB // or appropriate category
    
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        // Validate params
        val requiredParam = params["required_param"]?.toString()
            ?: return ToolResult(false, null, "Missing required_param")
        
        // Execute tool logic
        return try {
            val result = performOperation(requiredParam)
            ToolResult(success = true, data = result)
        } catch (e: Exception) {
            ToolResult(success = false, data = null, error = e.message)
        }
    }
    
    override suspend fun healthCheck(): HealthStatus {
        val start = System.currentTimeMillis()
        return try {
            // Perform health check (e.g., ping endpoint)
            val latency = System.currentTimeMillis() - start
            HealthStatus(isHealthy = true, latencyMs = latency, message = "OK")
        } catch (e: Exception) {
            HealthStatus(isHealthy = false, latencyMs = -1, message = e.message ?: "Error")
        }
    }
    
    override fun getParamsSchema(): JSONObject = JSONObject().apply {
        put("required_param", JSONObject().apply {
            put("type", "string")
            put("required", true)
            put("description", "Parameter description")
        })
    }
}
```

### Step 2: Register Tool

In `AgentToolManager.kt`:

```kotlin
private fun registerAllTools() {
    // ... existing tools
    registry.register(MyCustomTool(client))
}
```

### Step 3: Add to Router (Optional)

In `ToolRouter.kt`:

```kotlin
containsAny(normalizedRequest, "trigger", "کلمه_کلیدی") ->
    registry.getTool("my_custom_tool")
```

### Step 4: Update AgentBridge (Optional)

In `AgentBridge.kt` `detectToolIntent()`:

```kotlin
containsAny(lower, "trigger_word") -> {
    ToolIntent("my_custom_tool", mapOf("required_param" to extractedValue))
}
```

## Best Practices

### Error Handling

```kotlin
override suspend fun execute(params: Map<String, Any>): ToolResult {
    return try {
        // Tool logic
    } catch (e: IOException) {
        ToolResult(false, null, "Network error: ${e.message}")
    } catch (e: Exception) {
        ToolResult(false, null, "Unexpected error: ${e.message}")
    }
}
```

### Timeouts

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()
```

### Health Check Caching

ToolRegistry automatically caches health checks for 5 minutes. No need to implement your own caching.

### Logging

```kotlin
import android.util.Log

companion object {
    private const val TAG = "MyCustomTool"
}

Log.d(TAG, "Tool execution started")
Log.e(TAG, "Tool execution failed", exception)
```

### User Agent

```kotlin
val request = Request.Builder()
    .url(url)
    .header("User-Agent", "ARIA-Agent/1.0")
    .build()
```

## Primary + Fallback Pattern

```kotlin
private val searchEngines = listOf(
    SearchEngine.Primary,
    SearchEngine.Fallback
)

for (engine in searchEngines) {
    try {
        val result = searchWith(engine, query)
        if (result.isNotEmpty()) {
            return ToolResult(success = true, data = result)
        }
    } catch (e: Exception) {
        Log.w(TAG, "${engine.name} failed: ${e.message}")
        continue
    }
}

return ToolResult(false, null, "All engines failed")
```

## Health Check / Doctor

### Running Health Check

```kotlin
val toolManager = AgentToolManager()

// Check single tool
val status = toolManager.checkToolHealth("web_search")

// Check all tools
val allStatus = toolManager.checkAllToolsHealth()

// Full doctor report
val report = toolManager.runDoctorCheck()
println(report.toReadableString())
```

### Doctor Report Format

```
🔧 ARIA Agent Tools Health Report
==================================================
Total Tools: 12
✅ Healthy: 8
❌ Unhealthy: 4

Details:
  ✅ web_search: OK (245ms)
  ✅ web_browse: OK (312ms)
  ✅ github_search: OK (423ms)
  ❌ twitter_search: Twitter API requires authentication (0ms)
  ...

Recommendations:
  • twitter_search: Configure API credentials
  • facebook_search: Configure API credentials
```

## Testing

### Unit Test Example

```kotlin
@Test
fun testWebSearchTool() = runBlocking {
    val client = OkHttpClient()
    val tool = WebSearchTool(client)
    
    val result = tool.execute(mapOf("query" to "Kotlin Android"))
    
    assertTrue(result.success)
    assertNotNull(result.data)
}
```

### Integration Test

```kotlin
@Test
fun testToolRouter() = runBlocking {
    val manager = AgentToolManager()
    
    val result = manager.routeRequest("search for Kotlin tutorials")
    
    assertTrue(result.success)
    assertNotNull(result.data)
}
```

## Performance Considerations

### Parallel Execution

ToolRegistry uses coroutines for parallel health checks:

```kotlin
suspend fun checkAllHealth(): Map<String, HealthStatus> = coroutineScope {
    tools.keys.map { name ->
        async { name to checkHealth(name, forceRefresh = true) }
    }.awaitAll().toMap()
}
```

### Memory Management

- Use `use {}` for OkHttp responses
- Avoid loading large files into memory
- Implement streaming for large content

## Security

### Input Validation

```kotlin
val url = params["url"]?.toString()
    ?: return ToolResult(false, null, "URL is required")

if (!url.startsWith("http")) {
    return ToolResult(false, null, "Invalid URL scheme")
}
```

### HTTPS Enforcement

```kotlin
val request = Request.Builder()
    .url(url)
    .build()

// OkHttp enforces HTTPS by default
```

### Secret Management

Never hardcode API keys:

```kotlin
// ✅ Good
val apiKey = getSharedPreferences("aria", MODE_PRIVATE)
    .getString("api_key", "")

// ❌ Bad
val apiKey = "sk-1234567890abcdef"
```

## Troubleshooting

### Tool Not Executing

1. Check tool is registered in `AgentToolManager`
2. Verify tool name matches exactly
3. Check logs for exceptions

### Health Check Failing

1. Check network connectivity
2. Verify API endpoints are accessible
3. Check for rate limiting

### Router Not Selecting Tool

1. Add more keywords to `ToolRouter`
2. Check keyword matching logic
3. Test with explicit tool name

## Contributing

When contributing new tools:

1. Follow existing tool patterns
2. Implement all interface methods
3. Add comprehensive error handling
4. Write unit tests
5. Update this documentation
6. Update README.md

## References

- [Agent Reach Architecture](https://github.com/example/agent-reach)
- [OkHttp Documentation](https://square.github.io/okhttp/)
- [JSoup Documentation](https://jsoup.org/)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
