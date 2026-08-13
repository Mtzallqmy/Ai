package com.mtzallqmy.aiagent.provider.compatible

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
 * Generic OpenAI-compatible provider: the user supplies base URL, auth header value,
 * model id, and optional extra headers. Used for Anthropic-compatible, LM Studio,
 * Ollama, LiteLLM, Together, DeepSeek, OpenRouter (subclass) etc.
 */
open class OpenAiCompatibleProvider(
    override val providerId: String = "openai-compatible",
    override val name: String = "OpenAI Compatible",
    private val baseUrlProvider: suspend () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val authHeaderName: String = "Authorization",
    private val authHeaderValueProvider: (String?) -> String = { key -> "Bearer ${key.orEmpty()}" },
    private val extraHeadersProvider: () -> Map<String, String> = { emptyMap() },
    private val defaultModel: String = "",
) : AiProvider {

    protected val client = SafeHttpClient.create()
    protected val json = Json { ignoreUnknownKeys = true }

    protected open suspend fun completionsEndpoint(): String {
        val base = baseUrlProvider()?.trimEnd('/') ?: throw IllegalStateException("No base URL")
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    protected open suspend fun modelsEndpoint(): String {
        val base = baseUrlProvider()?.trimEnd('/') ?: throw IllegalStateException("No base URL")
        return if (base.endsWith("/models")) base else "$base/models"
    }
    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val base = baseUrlProvider() ?: error("No base URL configured")
            val key = apiKeyProvider()
            val req = Request.Builder()
                .url(modelsEndpoint())
                .header(authHeaderName, authHeaderValueProvider(key))
                .apply { extraHeadersProvider().forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string() ?: "[]"
                val entries = try {
                    json.decodeFromString<CompatibleModelList>(raw).data
                } catch (e: Exception) {
                    // Fallback: treat body as a bare JSON array
                    try {
                        json.decodeFromString<List<CompatibleModelEntry>>(raw)
                    } catch (e2: Exception) {
                        emptyList()
                    }
                }
                val models = entries.map { e ->
                    AiModel(id = e.id, name = e.id, providerId = providerId,
                        capabilities = ModelCapabilities(chat = true, streaming = true))
                }
                Result.success(models)
            }
        } catch (e: Throwable) { Result.failure(mapError(e)) }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKeyProvider()
            val req = Request.Builder()
                .url(completionsEndpoint())
                .header(authHeaderName, authHeaderValueProvider(key))
                .header("Content-Type", "application/json")
                .post(("{\"model\":\"" + defaultModel.ifEmpty { "default" } + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":5,\"stream\":false}").toRequestBody("application/json".toMediaType()))
                .apply { extraHeadersProvider().forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> Unit
                    resp.code in 401..403 -> throw ProviderError.AuthenticationError("Invalid credentials for $providerId")
                    resp.code == 429 -> throw ProviderError.RateLimitError()
                    resp.code == 404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "$providerId request failed")
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val base = baseUrlProvider()
        val key = apiKeyProvider()
        if (base == null) {
            trySend(GenerationEvent.GenerationFailed(ProviderError.ProviderError_(500, "Base URL not configured")))
            close(); return@callbackFlow
        }
        val payload = buildPayload(request)
        val req = Request.Builder()
            .url(completionsEndpoint())
            .header(authHeaderName, authHeaderValueProvider(key))
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .apply { extraHeadersProvider().forEach { (k, v) -> header(k, v) } }
            .build()
        val response = client.newCall(req).execute()
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
                    if (data == "[DONE]") {
                        trySend(GenerationEvent.GenerationCompleted(""))
                        break
                    }
                    val chunk = try {
                        json.decodeFromString<CompatibleChunk>(data)
                    } catch (e: Exception) { continue }
                    chunk.usage?.let { u ->
                        trySend(GenerationEvent.Usage(u.promptTokens ?: 0, u.completionTokens ?: 0))
                    }
                    val choice = chunk.choices.firstOrNull() ?: continue
                    val delta = choice.delta
                    delta.content?.let { if (it.isNotEmpty()) trySend(GenerationEvent.TextDelta(it)) }
                    val tools = delta.toolCalls ?: emptyList()
                    for (tc in tools) {
                        trySend(GenerationEvent.ToolCallStarted(tc.id ?: "", tc.function?.name ?: ""))
                        tc.function?.arguments?.let { trySend(GenerationEvent.ToolCallArgumentsDelta(tc.id ?: "", it)) }
                    }
                    if (choice.finishReason != null) trySend(GenerationEvent.GenerationCompleted(""))
                }
            }
        } catch (e: Exception) {
            trySend(GenerationEvent.GenerationFailed(mapError(e)))
        } finally {
            response.close()
        }
        awaitClose { response.close() }
    }

    protected open fun buildPayload(request: GenerationRequest): String {
        val sb = StringBuilder()
        sb.append("{\"model\":\"").append(request.modelId.ifEmpty { defaultModel }).append("\",\"messages\":")
        sb.append("[")
        request.messages.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(",")
            sb.append("{\"role\":\"").append(m.role.name.lowercase()).append("\",\"content\":").append(jsonString(m.content)).append("}")
        }
        sb.append("],\"temperature\":").append(request.temperature).append(",\"stream\":true")
        request.maxTokens?.let { sb.append(",\"max_tokens\":").append(it) }
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":")
            sb.append("[")
            request.tools.forEachIndexed { idx, t ->
                if (idx > 0) sb.append(",")
                sb.append("{\"type\":\"function\",\"function\":{\"name\":\"").append(t.id).append("\",\"description\":").append(jsonString(t.description)).append(",\"parameters\":")
                sb.append(t.inputSchema.ifBlank { "{\"type\":\"object\",\"properties\":{}}" })
                sb.append("}}")
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    protected fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
        }
        append('"')
    }

    protected fun mapHttpError(code: Int): ProviderError = when (code) {
        401, 403 -> ProviderError.AuthenticationError("$providerId authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "$providerId HTTP $code")
    }

    protected fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
        else -> ProviderError.ProviderError_(500, e.message ?: "$providerId error")
    }
}

@Serializable
private data class CompatibleModelList(val data: List<CompatibleModelEntry> = emptyList())

@Serializable
private data class CompatibleModelEntry(val id: String)

@Serializable
private data class CompatibleChunk(
    val choices: List<CompatibleChoice> = emptyList(),
    val usage: CompatibleUsage? = null,
)

@Serializable
private data class CompatibleChoice(
    val delta: CompatibleDelta = CompatibleDelta(),
    val finishReason: String? = null,
)

@Serializable
private data class CompatibleDelta(
    val content: String? = null,
    val toolCalls: List<CompatibleToolCall>? = null,
)

@Serializable
private data class CompatibleToolCall(
    val id: String? = null,
    val index: Int? = null,
    val function: CompatibleFunction? = null,
)

@Serializable
private data class CompatibleFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class CompatibleUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)
