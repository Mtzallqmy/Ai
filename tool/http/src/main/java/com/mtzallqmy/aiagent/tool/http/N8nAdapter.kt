package com.mtzallqmy.aiagent.tool.http

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * n8n adapter — concepts studied from n8n (Sustainable Use license;
 * clean-room REST integration): trigger a remote n8n workflow via webhook
 * and fetch its latest execution result via the n8n public API.
 *
 * Requires a user-configured n8n instance (baseUrl + API key). Local-only
 * webhook triggers (agent fires a workflow) are always safe; execution
 * listing requires the API key.
 */
class N8nAdapter(
    private val baseUrlProvider: suspend () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient,
) {
    /** Webhook trigger tool — fires a workflow synchronously-ish (POST). */
    val triggerWorkflowTool: AgentTool<JsonElement, JsonElement> = object : AgentTool<JsonElement, JsonElement> {
        override val descriptor = ToolDescriptor(
            id = "n8n.trigger_workflow",
            displayName = "Trigger n8n Workflow",
            description = "Send a webhook payload to a remote n8n workflow",
            inputSchema = """{"type":"object","required":["webhookUrl"],"properties":{"webhookUrl":{"type":"string"},"payload":{"type":"object"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.COMMUNICATION,
            requiredCapabilities = setOf(CapabilityId("http")),
            timeoutMs = 30_000L,
        )

        override suspend fun availability(context: ToolContext): ToolAvailability {
            return if (baseUrlProvider() != null) ToolAvailability.Available
            else ToolAvailability.Unavailable("n8n base URL not configured")
        }

        override suspend fun execute(input: JsonElement, context: ToolContext): JsonElement {
            val args = input.jsonObject
            val webhookUrl = args["webhookUrl"]?.let { (it as? JsonPrimitive)?.content }
                ?: error("webhookUrl required")
            val payload = args["payload"]?.toString() ?: "{}"
            return withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    buildJsonObject {
                        put("status_code", JsonPrimitive(response.code))
                        put("response", JsonPrimitive(body.take(10_000)))
                    }
                }
            }
        }
    }

    /** Fetch the latest execution status of a workflow via the n8n API. */
    suspend fun latestExecution(workflowId: String): JsonElement? {
        val base = baseUrlProvider() ?: return null
        val key = apiKeyProvider() ?: return null
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$base/api/v1/executions?workflowId=$workflowId&limit=1")
                .header("X-N8N-API-KEY", key)
                .get()
                .build()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    Json.parseToJsonElement(response.body?.string() ?: "{}")
                }
            }.getOrNull()
        }
    }

    /** Check the adapter is configured. */
    suspend fun isConfigured(): Boolean = baseUrlProvider() != null
}
