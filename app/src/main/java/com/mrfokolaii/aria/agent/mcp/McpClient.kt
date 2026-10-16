package com.mrfokolaii.aria.agent.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal MCP client over HTTP JSON-RPC (tools/list + tools/call).
 * Works with Streamable-HTTP style endpoints that accept POST JSON-RPC.
 * Does not require the full official Kotlin SDK (keeps Android deps light).
 */
class McpClient(
    private val serverUrl: String,
    private val httpClient: OkHttpClient = defaultClient()
) {
    private val nextId = AtomicLong(1)
    @Volatile private var initialized = false
    @Volatile private var sessionId: String? = null

    data class McpToolInfo(
        val name: String,
        val description: String,
        val inputSchema: JSONObject?
    )

    data class McpCallResult(
        val success: Boolean,
        val text: String,
        val raw: JSONObject? = null,
        val error: String? = null
    )

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        if (serverUrl.isBlank()) return@withContext false
        try {
            val params = JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put(
                    "clientInfo",
                    JSONObject().put("name", "aria-android").put("version", "0.4.0")
                )
            val result = rpc("initialize", params)
            if (result != null) {
                initialized = true
                try {
                    rpcNotify("notifications/initialized", JSONObject())
                } catch (_: Exception) {}
                Log.i(TAG, "MCP initialized: $serverUrl")
                true
            } else {
                Log.w(TAG, "MCP initialize failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "MCP initialize error", e)
            false
        }
    }

    suspend fun listTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        if (!initialize()) return@withContext emptyList()
        try {
            val result = rpc("tools/list", JSONObject()) ?: return@withContext emptyList()
            val tools = result.optJSONArray("tools") ?: JSONArray()
            val out = mutableListOf<McpToolInfo>()
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                val name = t.optString("name")
                if (name.isBlank()) continue
                out.add(
                    McpToolInfo(
                        name = name,
                        description = t.optString("description", name),
                        inputSchema = t.optJSONObject("inputSchema")
                    )
                )
            }
            Log.i(TAG, "MCP tools: ${out.map { it.name }}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "listTools failed", e)
            emptyList()
        }
    }

    suspend fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): McpCallResult =
        withContext(Dispatchers.IO) {
            if (!initialize()) {
                return@withContext McpCallResult(false, "", error = "MCP not connected")
            }
            try {
                val argsJson = JSONObject()
                arguments.forEach { (k, v) ->
                    when (v) {
                        null -> argsJson.put(k, JSONObject.NULL)
                        is Number -> argsJson.put(k, v)
                        is Boolean -> argsJson.put(k, v)
                        is JSONObject -> argsJson.put(k, v)
                        is JSONArray -> argsJson.put(k, v)
                        else -> argsJson.put(k, v.toString())
                    }
                }
                val params = JSONObject()
                    .put("name", name)
                    .put("arguments", argsJson)
                val result = rpc("tools/call", params)
                    ?: return@withContext McpCallResult(false, "", error = "Empty MCP response")

                if (result.optBoolean("isError", false)) {
                    val errText = extractText(result).ifBlank { "MCP tool error" }
                    return@withContext McpCallResult(false, errText, result, errText)
                }
                val text = extractText(result)
                McpCallResult(true, text, result)
            } catch (e: Exception) {
                Log.e(TAG, "callTool $name failed", e)
                McpCallResult(false, "", error = e.message ?: "call failed")
            }
        }

    suspend fun healthPing(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!initialize()) return@withContext false to "initialize failed"
            val tools = listTools()
            true to "OK (${tools.size} tools)"
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }

    private fun extractText(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return result.toString().take(2000)
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(item.optString("text"))
                }
                else -> {
                    val t = item.optString("text")
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    }
                }
            }
        }
        return sb.toString().ifBlank { result.toString().take(2000) }
    }

    private fun rpc(method: String, params: JSONObject): JSONObject? {
        val id = nextId.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

        val reqBuilder = Request.Builder()
            .url(serverUrl.trim())
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${raw.take(300)}")
            }
            val jsonText = if (raw.trimStart().startsWith("event:") || raw.contains("data:")) {
                raw.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .firstOrNull { it.startsWith("{") }
                    ?: raw
            } else raw

            val json = JSONObject(jsonText)
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                throw Exception(err.optString("message", err.toString()))
            }
            return json.optJSONObject("result")
        }
    }

    private fun rpcNotify(method: String, params: JSONObject) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        val reqBuilder = Request.Builder()
            .url(serverUrl.trim())
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
        sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }
        try {
            httpClient.newCall(reqBuilder.build()).execute().close()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "McpClient"
        private const val PROTOCOL_VERSION = "2024-11-05"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

        const val PREF_ENABLED = "mcp_enabled"
        const val PREF_URL = "mcp_server_url"
    }
}
