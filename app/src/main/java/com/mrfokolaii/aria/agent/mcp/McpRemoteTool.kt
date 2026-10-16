package com.mrfokolaii.aria.agent.mcp

import com.mrfokolaii.aria.agent.tools.AgentTool
import com.mrfokolaii.aria.agent.tools.HealthStatus
import com.mrfokolaii.aria.agent.tools.ToolCategory
import com.mrfokolaii.aria.agent.tools.ToolResult
import org.json.JSONObject

/** Exposes one remote MCP tool as a local AgentTool (name prefixed with mcp_). */
class McpRemoteTool(
    private val client: McpClient,
    private val remoteName: String,
    private val remoteDescription: String,
    private val schema: JSONObject?
) : AgentTool {

    override val name: String = "mcp_$remoteName"
    override val description: String = "[MCP] $remoteDescription"
    override val category: ToolCategory = ToolCategory.WEB

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val result = client.callTool(remoteName, params)
        return if (result.success) {
            ToolResult(
                success = true,
                data = mapOf(
                    "text" to result.text,
                    "mcp_tool" to remoteName
                ),
                metadata = mapOf("source" to "mcp", "tool" to remoteName)
            )
        } else {
            ToolResult(false, null, result.error ?: "MCP call failed")
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        val start = System.currentTimeMillis()
        val (ok, msg) = client.healthPing()
        return HealthStatus(ok, System.currentTimeMillis() - start, msg)
    }

    override fun getParamsSchema(): JSONObject =
        schema ?: JSONObject().put("type", "object")
}
