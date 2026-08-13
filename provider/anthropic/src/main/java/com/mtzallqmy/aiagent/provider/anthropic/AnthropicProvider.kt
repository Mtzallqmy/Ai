package com.mtzallqmy.aiagent.provider.anthropic

import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.network.SafeHttpClient
import com.mtzallqmy.aiagent.providers.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Real Anthropic Messages API provider with SSE streaming and tool use.
 * Supports claude-opus-4-7 and other models configured by the user.
 */
class AnthropicProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val apiVersion: String = "2025-06-01",
    private val defaultModel: String = "claude-opus-4-7",
) : AiProvider {

    override val providerId: String = "anthropic"
    override val name: String = "Anthropic (Claude)"

    private val client = SafeHttpClient.create()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No Anthropic API key")
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .header("x-api-key", key)
                .header("anthropic-version", apiVersion)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw mapHttpError(resp.code)
                val models = json.decodeFromString<List<AnthropicModelEntry>>(resp.body?.string() ?: "[]").map { m ->
                    AiModel(
                        id = m.id,
                        name = m.displayName ?: m.id,
                        providerId = providerId,
                        capabilities = ModelCapabilities(
                            chat = true,
                            streaming = true,
                            toolCalling = true,
                            reasoning = m.id.contains("opus", ignoreCase = true) || m.id.contains("sonnet", ignoreCase = true),
                            contextWindow = m.maxInputTokens ?: 200_000,
                            maxOutputTokens = m.maxOutputTokens ?: 16_000,
                        ),
                    )
                }
                Result.success(models)
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No Anthropic API key")
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key)
                .header("anthropic-version", apiVersion)
                .header("Content-Type", "application/json")
                .post(("{\"model\":\"" + defaultModel + "\",\"max_tokens\":5,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"stream\":false}").toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.success(Unit)
                    resp.code in 401..403 -> throw ProviderError.AuthenticationError("Invalid Anthropic key")
                    resp.code == 429 -> throw ProviderError.RateLimitError()
                    resp.code == 404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "Anthropic error")
                }
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val key = apiKeyProvider()
        if (key == null) {
            trySend(GenerationEvent.GenerationFailed(ProviderError.AuthenticationError("No Anthropic API key")))
            close(); return@callbackFlow
        }
        val payload = buildPayload(request)
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", apiVersion)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            trySend(GenerationEvent.GenerationFailed(ProviderError.NetworkError(e.message ?: "Network failure")))
            close(); return@callbackFlow
        }
        if (!response.isSuccessful) {
            trySend(GenerationEvent.GenerationFailed(mapHttpError(response.code)))
            response.close(); close(); return@callbackFlow
        }
        try {
            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val event = try {
                        json.decodeFromString<AnthropicStreamEvent>(data)
                    } catch (e: Exception) {
                        continue
                    }
                    when (event.type) {
                        "content_block_start" -> {
                            val block = event.contentBlock
                            when (block?.type) {
                                "text" -> block.text?.takeIf { it.isNotEmpty() }?.let {
                                    trySend(GenerationEvent.TextDelta(it))
                                }
                                "tool_use" -> trySend(GenerationEvent.ToolCallStarted(block.id ?: "", block.name ?: ""))
                                else -> {}
                            }
                        }
                        "content_block_delta" -> {
                            val delta = event.delta
                            delta?.text?.takeIf { it.isNotEmpty() }?.let { trySend(GenerationEvent.TextDelta(it)) }
                            delta?.partialJson?.takeIf { it.isNotEmpty() }?.let {
                                trySend(GenerationEvent.ToolCallArgumentsDelta(event.index.toString(), it))
                            }
                        }
                        "message_delta" -> {
                            if (event.delta?.stopReason != null) trySend(GenerationEvent.GenerationCompleted(""))
                            event.usage?.let { u ->
                                trySend(GenerationEvent.Usage(u.inputTokens ?: 0, u.outputTokens ?: 0))
                            }
                        }
                        "error" -> {
                            trySend(GenerationEvent.GenerationFailed(
                                ProviderError.ProviderError_(event.error?.code?.toIntOrNull() ?: 500, event.error?.message ?: "Anthropic error"),
                            ))
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Exception) {
            trySend(GenerationEvent.GenerationFailed(mapError(e)))
        } finally {
            response.close()
        }
        awaitClose { response.close() }
    }

    private fun buildPayload(request: GenerationRequest): String {
        val system = request.messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        val chatMessages = request.messages.filter { it.role != MessageRole.SYSTEM }
        val sb = StringBuilder()
        sb.append("{\"model\":\"").append(request.modelId.ifEmpty { defaultModel }).append("\",\"max_tokens\":").append(request.maxTokens ?: 4096).append(",\"stream\":true")
        system?.let { sb.append(",\"system\":").append(jsonString(it)) }
        sb.append(",\"messages\":")
        sb.append("[")
        chatMessages.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(",")
            sb.append("{\"role\":\"").append(m.role.name.lowercase()).append("\",\"content\":").append(jsonString(m.content)).append("}")
        }
        sb.append("]")
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":")
            sb.append("[")
            request.tools.forEachIndexed { idx, t ->
                if (idx > 0) sb.append(",")
                sb.append("{\"name\":\"").append(t.id).append("\",\"description\":").append(jsonString(t.description)).append(",\"input_schema\":")
                sb.append(t.inputSchema.ifBlank { "{\"type\":\"object\",\"properties\":{}}" })
                sb.append("}")
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
        }
        append('"')
    }

    private fun mapHttpError(code: Int): ProviderError = when (code) {
        401, 403 -> ProviderError.AuthenticationError("Anthropic authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "Anthropic HTTP $code")
    }

    private fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
                else -> ProviderError.ProviderError_(500, e.message ?: "Anthropic error")
    }
}

@Serializable
private data class AnthropicModelEntry(
    val id: String,
    val displayName: String? = null,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
private data class AnthropicStreamEvent(
    val type: String = "",
    val index: Int = 0,
    val contentBlock: AnthropicBlock? = null,
    val delta: AnthropicDelta? = null,
    val usage: AnthropicUsage? = null,
    val error: AnthropicError? = null,
)

@Serializable
private data class AnthropicBlock(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
    val text: String? = null,
)

@Serializable
private data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    val partialJson: String? = null,
    val stopReason: String? = null,
)

@Serializable
private data class AnthropicUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

@Serializable
private data class AnthropicError(
    val type: String? = null,
    val code: String? = null,
    val message: String? = null,
)
