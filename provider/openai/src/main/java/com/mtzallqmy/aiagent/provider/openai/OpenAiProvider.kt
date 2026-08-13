package com.mtzallqmy.aiagent.provider.openai

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
import okhttp3.Response

/**
 * Real OpenAI provider: chat completions with Server-Sent Events streaming
 * and function-calling tool use. Wire format is normalized to GenerationEvents.
 */
class OpenAiProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultModel: String = "gpt-4o-mini",
) : AiProvider {
    override val providerId = "openai"
    override val name = "OpenAI"

    private val client = SafeHttpClient.create()
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: error("No API key")
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string() ?: ""
                val envelope = json.decodeFromString<ModelListEnvelope>(raw)
                val models = envelope.data.map { m ->
                    AiModel(
                        id = m.id,
                        name = m.id,
                        providerId = providerId,
                        capabilities = mapCapabilities(m.id),
                    )
                }
                Result.success(models)
            }
        } catch (e: Throwable) { Result.failure(mapError(e)) }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No API key configured")
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> Unit
                    401, 403 -> throw ProviderError.AuthenticationError("Invalid API key")
                    429 -> throw ProviderError.RateLimitError()
                    404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "OpenAI request failed")
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val key = apiKeyProvider() ?: run {
            trySend(GenerationEvent.GenerationFailed(ProviderError.AuthenticationError("No API key configured")))
            close()
            return@callbackFlow
        }
        val payload = buildPayload(request)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val response: Response = client.newCall(req).execute()
        if (!response.isSuccessful) {
            trySend(GenerationEvent.GenerationFailed(mapHttpError(response.code)))
            response.close()
            close()
            return@callbackFlow
        }
        try {
            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        trySend(GenerationEvent.GenerationCompleted(""))
                        break
                    }
                    val chunk = try {
                        json.decodeFromString<ChatCompletionChunk>(data)
                    } catch (e: Exception) {
                        continue
                    }
                    if (chunk.usage != null) {
                        trySend(GenerationEvent.Usage(chunk.usage.promptTokens ?: 0, chunk.usage.completionTokens ?: 0))
                    }
                    val choice = chunk.choices.firstOrNull() ?: continue
                    val delta = choice.delta
                    delta.content?.let { if (it.isNotEmpty()) trySend(GenerationEvent.TextDelta(it)) }
                    val tools = delta.toolCalls ?: emptyList()
                    for (tc in tools) {
                        val callId = tc.id
                        val functionName = tc.function?.name
                        val args = tc.function?.arguments
                        if (callId != null || functionName != null) {
                            trySend(GenerationEvent.ToolCallStarted(callId ?: "", functionName ?: ""))
                        }
                        if (args != null) {
                            trySend(GenerationEvent.ToolCallArgumentsDelta(callId ?: "", args))
                        }
                    }
                    if (choice.finishReason != null) {
                        trySend(GenerationEvent.GenerationCompleted(""))
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
        val messages = request.messages.map { mapOf("role" to it.role.name.lowercase(), "content" to it.content) }
        val tools = request.tools.takeIf { it.isNotEmpty() }?.map {
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to it.id,
                    "description" to it.description,
                    "parameters" to json.decodeFromString<Map<String, Any>>(it.inputSchema.ifBlank { """{"type":"object","properties":{}}""" }),
                ),
            )
        }
        val body = mutableMapOf<String, Any>(
            "model" to request.modelId.ifEmpty { defaultModel },
            "messages" to messages,
            "temperature" to request.temperature,
            "stream" to true,
        )
        request.maxTokens?.let { body["max_tokens"] = it }
        if (!tools.isNullOrEmpty()) body["tools"] = tools
        return buildManualJson(body)
    }

    private fun buildManualJson(body: Map<String, Any>): String = buildString {
        append('{')
        body.entries.forEachIndexed { idx, (k, v) ->
            if (idx > 0) append(',')
            append('"').append(k).append("\":").append(jsonValue(v))
        }
        append('}')
    }

    private fun jsonValue(v: Any): String = when (v) {
        is String -> "\"${v.replace("\"", "\\\"")}\""
        is Boolean -> v.toString()
        is Number -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { jsonValue(it!!) }
        is Map<*, *> -> buildString {
            append('{')
            v.entries.forEachIndexed { idx, (k, w) ->
                if (idx > 0) append(',')
                append('"').append(k).append("\":").append(jsonValue(w!!))
            }
            append('}')
        }
        else -> "\"$v\""
    }

    private fun mapCapabilities(modelId: String): ModelCapabilities = ModelCapabilities(
        chat = true,
        streaming = true,
        toolCalling = true,
        parallelToolCalling = modelId.contains("gpt-4", ignoreCase = true),
        vision = modelId.contains("gpt-4o", ignoreCase = true) || modelId.contains("gpt-4.1", ignoreCase = true),
        jsonMode = true,
        contextWindow = when {
            modelId.startsWith("gpt-4o") -> 128_000
            modelId.startsWith("gpt-4.1") -> 1_048_576
            modelId.startsWith("gpt-4") -> 128_000
            modelId.startsWith("gpt-3.5") -> 16_385
            else -> 128_000
        },
    )

    private fun mapHttpError(code: Int): ProviderError = when (code) {
        401, 403 -> ProviderError.AuthenticationError("OpenAI authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "OpenAI HTTP $code")
    }

    private fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is java.io.IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
        else -> ProviderError.ProviderError_(500, e.message ?: "Unknown OpenAI error")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}

@Serializable
private data class ModelListEnvelope(val data: List<ModelEntry> = emptyList())

@Serializable
private data class ModelEntry(val id: String)

@Serializable
private data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChunkUsage? = null,
)

@Serializable
private data class ChunkChoice(
    val delta: ChunkDelta = ChunkDelta(),
    val finishReason: String? = null,
)

@Serializable
private data class ChunkDelta(
    val content: String? = null,
    val toolCalls: List<ChunkToolCall>? = null,
)

@Serializable
private data class ChunkToolCall(
    val id: String? = null,
    val function: ChunkFunction? = null,
)

@Serializable
private data class ChunkFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class ChunkUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)
