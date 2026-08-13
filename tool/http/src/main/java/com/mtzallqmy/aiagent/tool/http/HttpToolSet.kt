package com.mtzallqmy.aiagent.tool.http

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.network.SafeHttpClient
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Real HTTP tools: http.get/post/put/patch/delete/head — network calls actually
 * performed through SafeHttpClient (SSRF-protected). Responses are bounded.
 */
class HttpToolSet(
    private val maxResponseChars: Int = 30_000,
    private val allowedSchemes: Set<String> = setOf("https", "http"),
) {
    private val client = SafeHttpClient.create()

    val tools: List<AgentTool<Any, Any>> = listOf(
        HttpMethodTool("http.get", "GET"),
        HttpMethodTool("http.post", "POST"),
        HttpMethodTool("http.put", "PUT"),
        HttpMethodTool("http.patch", "PATCH"),
        HttpMethodTool("http.delete", "DELETE"),
        HttpMethodTool("http.head", "HEAD"),
    )

    private inner class HttpMethodTool(overrideId: String, private val method: String) : AgentTool<Any, Any> {
        override val descriptor = ToolDescriptor(
            id = overrideId, displayName = method,
            description = "Perform an HTTP $method request against a remote API",
            inputSchema = """{"type":"object","required":["url"],"properties":{"url":{"type":"string"},"headers":{"type":"object"},"body":{"type":"string"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.COMMUNICATION, requiredCapabilities = setOf(CapabilityId("network")), timeoutMs = 30_000L,
        )
        override suspend fun availability(context: ToolContext) = ToolAvailability.Available
        override suspend fun execute(input: Any, context: ToolContext): Any = withContext(Dispatchers.IO) {
            val args = input as? JsonObject ?: error("arguments object required")
            val url = args["url"]?.jsonPrimitive?.content ?: error("url required")
            validateUrl(url)
            val headers = (args["headers"] as? JsonObject)?.entries?.associate { (k, v) -> k to v.jsonPrimitive.content } ?: emptyMap()
            val bodyStr = args["body"]?.jsonPrimitive?.content
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                when (method) {
                    "GET", "HEAD" -> method
                    "POST" -> post((bodyStr ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                    "PUT" -> put((bodyStr ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                    "PATCH" -> patch((bodyStr ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                    "DELETE" -> delete()
                    else -> get()
                }
            }.build()
            client.newCall(request).execute().use { resp ->
                val responseHeaders = resp.headers.toMultimap().entries.take(12).map { (k, v) -> "$k: ${v.take(100)}" }
                val responseBody = if (method == "HEAD") "" else {
                    resp.body?.string()?.take(maxResponseChars) ?: ""
                }
                buildJsonObject {
                    put("status_code", JsonPrimitive(resp.code))
                    put("headers", JsonArray(responseHeaders.map { JsonPrimitive(it) }))
                    put("body", JsonPrimitive(responseBody))
                }
            }
        }
    }

    private fun validateUrl(url: String) {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme !in allowedSchemes) error("Scheme not allowed: $scheme")
        if (url.isBlank()) error("Empty URL")
    }
}
