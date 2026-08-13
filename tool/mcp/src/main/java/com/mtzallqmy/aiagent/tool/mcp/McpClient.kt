package com.mtzallqmy.aiagent.tool.mcp

import com.mtzallqmy.aiagent.network.SafeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real MCP client: JSON-RPC 2.0 over Streamable HTTP (MCP 2025-03-26 spec).
 * - initialize / notifications/initialized
 * - tools/list → discovery
 * - tools/call → execution with per-tool approval upstream in ToolRuntime
 * Secrets never logged; only server URLs and tool names.
 */
class McpClient(
    private val serverUrl: String,
    private val headers: Map<String, String> = emptyMap(),
) {
    private val client = SafeHttpClient.create()
    private val json = Json { ignoreUnknownKeys = true }
    private val msgId = AtomicInteger(1)

    private var initialized = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        val result = callRpc("initialize", buildJsonObject {
            put("protocolVersion", JsonPrimitive("2025-03-26"))
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", JsonPrimitive("aegis-agent"))
                put("version", JsonPrimitive("1.0.0"))
            })
        })
        val ok = result != null
        if (ok) {
            callRpc("notifications/initialized", null)
            initialized = true
        }
        ok
    }

    /** Discover remote tool descriptors. */
    suspend fun listTools(): List<McpToolDescriptor> = withContext(Dispatchers.IO) {
        val body = callRpc("tools/list", null) ?: return@withContext emptyList()
        val toolsArray = (body as? JsonObject)?.get("tools") as? JsonArray ?: return@withContext emptyList()
        toolsArray.map { entry ->
            val obj = entry as JsonObject
            McpToolDescriptor(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = obj["inputSchema"]?.toString() ?: """{"type":"object","properties":{}}""",
            )
        }
    }

    /** Execute a remote MCP tool; returns the raw JSON result. */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        callRpc("tools/call", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", arguments)
        }) as? JsonObject
    }

    suspend fun close() {
        if (initialized) callRpc("notifications/disconnected", null)
    }

    private fun callRpc(method: String, params: JsonObject?): JsonElement? {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(msgId.getAndIncrement()))
            put("method", JsonPrimitive(method))
            params?.let { put("params", it) }
        }
        val request = Request.Builder()
            .url(serverUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                if (body.isBlank()) return null
                val element = json.parseToJsonElement(body)
                (element as? JsonObject)?.get("result") ?: element
            }
        } catch (e: Exception) {
            null
        }
    }
}

data class McpToolDescriptor(val name: String, val description: String, val inputSchema: String)
