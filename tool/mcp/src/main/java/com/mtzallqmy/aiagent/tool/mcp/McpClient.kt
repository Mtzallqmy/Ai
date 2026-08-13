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

    /** HEALTH: server-reported capabilities after initialize. */
    var serverCapabilities: JsonObject? = null
        private set

    private var initialized = false
    private var sessionId: String? = null

    /** HEALTH/RECONNECT: whether the transport is currently healthy. */
    val isHealthy: Boolean get() = initialized

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
        val obj = result as? JsonObject
        val ok = obj != null
        if (ok) {
            // Store server capabilities + optional Mcp-Session-Id (streamable HTTP).
            serverCapabilities = obj?.get("capabilities") as? JsonObject
            callRpc("notifications/initialized", null)
            initialized = true
        }
        ok
    }

    /** HEALTH CHECK: lightweight ping via tools/list; returns false on any failure. */
    suspend fun healthCheck(): Boolean = runCatching { listTools(); true }.getOrDefault(false)

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
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                sessionId?.let { header("Mcp-Session-Id", it) }
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                // Streamable HTTP: capture the Mcp-Session-Id for subsequent
                // requests (one session per initialize).
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                val rawBody = resp.body?.string() ?: return null
                if (rawBody.isBlank()) return null
                // Accept either plain JSON or SSE (event: message) payloads.
                val body = if (rawBody.trimStart().startsWith("event:")) {
                    extractSseData(rawBody)
                } else {
                    rawBody.trim()
                }
                if (body.isBlank()) return null
                runCatching { json.parseToJsonElement(body) }
                    .getOrNull()?.let { (it as? JsonObject)?.get("result") ?: it }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractSseData(sse: String): String {
        val dataLines = sse.lines()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
        return if (dataLines.isEmpty()) "" else dataLines.last()
    }
}

data class McpToolDescriptor(val name: String, val description: String, val inputSchema: String)
