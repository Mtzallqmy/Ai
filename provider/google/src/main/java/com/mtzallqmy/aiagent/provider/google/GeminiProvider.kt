package com.mtzallqmy.aiagent.provider.google

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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Real Gemini provider using the Generative Language REST API with SSE streaming.
 */
class GeminiProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val defaultModel: String = "gemini-2.5-flash",
) : AiProvider {
    override val providerId = "gemini"
    override val name = "Google Gemini"

    private val client = SafeHttpClient.create()
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: error("No API key")
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string() ?: "{}"
                val root = json.decodeFromString<GeminiModelList>(raw)
                val result = root.models.mapNotNull { m ->
                    val name = m.name ?: return@mapNotNull null
                    val id = name.removePrefix("models/")
                    val inputTokens = m.inputTokenLimit ?: 0
                    val outputTokens = m.outputTokenLimit ?: 0
                    val supportsGenContent = m.supportedGenerationMethods?.contains("generateContent") == true
                    if (!supportsGenContent) null
                    else AiModel(
                        id = id,
                        name = m.displayName ?: id,
                        providerId = providerId,
                        capabilities = ModelCapabilities(
                            chat = true,
                            streaming = true,
                            toolCalling = m.supportedGenerationMethods?.contains("generateContent") == true,
                            vision = id.contains("gemini", ignoreCase = true) && !id.endsWith("nano"),
                            reasoning = id.contains("gemini-2.5-pro", ignoreCase = true),
                            contextWindow = inputTokens,
                            maxOutputTokens = outputTokens,
                        ),
                        routing = ModelRoutingMetadata(
                            speedTier = if (id.contains("flash", true)) ModelSpeedTier.FAST else ModelSpeedTier.QUALITY,
                            codingOptimized = id.contains("code", true),
                        ),
                    )
                }
                Result.success(result)
            }
        } catch (e: Throwable) { Result.failure(mapError(e)) }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No Gemini API key")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$defaultModel:generateContent?key=$key"
            val body = """{"contents":[{"parts":[{"text":"ping"}]}]}"""
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> Unit
                    resp.code in 401..403 -> throw ProviderError.AuthenticationError("Invalid Gemini key")
                    resp.code == 429 -> throw ProviderError.RateLimitError()
                    resp.code == 404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "Gemini error")
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val key = apiKeyProvider()
        if (key == null) {
            trySend(GenerationEvent.GenerationFailed(ProviderError.AuthenticationError("No Gemini API key")))
            close(); return@callbackFlow
        }
        val model = request.modelId.ifEmpty { defaultModel }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$key"
        val payload = buildPayload(request)
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
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
                    val obj = try {
                        json.parseToJsonElement(data).toString()
                    } catch (e: Exception) { continue }
                    // Extract text parts from the response object
                    try {
                        val root = kotlinx.serialization.json.Json.parseToJsonElement(data)
                        val candidates = (root as? kotlinx.serialization.json.JsonObject)?.get("candidates")
                            ?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() } as? kotlinx.serialization.json.JsonObject
                        candidates?.get("content")?.let { content ->
                            val parts = (content as? kotlinx.serialization.json.JsonObject)?.get("parts") as? kotlinx.serialization.json.JsonArray
                            parts?.forEach { part ->
                                val text = (part as? kotlinx.serialization.json.JsonObject)?.get("text")?.jsonPrimitive?.content
                                if (!text.isNullOrBlank()) trySend(GenerationEvent.TextDelta(text))
                            }
                        }
                        candidates?.get("finishReason")?.let {
                            val reason = it.jsonPrimitive.content
                            if (reason == "STOP" || reason == "MAX_TOKENS") trySend(GenerationEvent.GenerationCompleted(""))
                        }
                        (root as? kotlinx.serialization.json.JsonObject)?.get("usageMetadata")?.let { u ->
                            val prompt = (u as? kotlinx.serialization.json.JsonObject)?.get("promptTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val completion = (u as? kotlinx.serialization.json.JsonObject)?.get("candidatesTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            trySend(GenerationEvent.Usage(prompt, completion))
                        }
                    } catch (e: Exception) {
                        // Continue on parse hiccups; errors surface through finishReason blocks
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
        sb.append("{\"contents\":")
        sb.append("[")
        chatMessages.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(",")
            val role = if (m.role == MessageRole.TOOL) "user" else if (m.role == MessageRole.ASSISTANT) "model" else "user"
            sb.append("{\"role\":\"").append(role).append("\",\"parts\":[{\"text\":").append(jsonString(m.content)).append("}]}")
        }
        sb.append("]")
        system?.let { sb.append(",\"systemInstruction\":{\"parts\":[{\"text\":").append(jsonString(it)).append("}]}") }
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":[{\"functionDeclarations\":[")
            request.tools.forEachIndexed { idx, t ->
                if (idx > 0) sb.append(",")
                sb.append("{\"name\":\"").append(t.id).append("\",\"description\":").append(jsonString(t.description)).append(",\"parameters\":")
                sb.append(t.inputSchema.ifBlank { "{\"type\":\"object\",\"properties\":{}}" })
                sb.append("}")
            }
            sb.append("]}]")
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
        401, 403 -> ProviderError.AuthenticationError("Gemini authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "Gemini HTTP $code")
    }

    private fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
        else -> ProviderError.ProviderError_(500, e.message ?: "Gemini error")
    }
}

@Serializable private data class GeminiModelList(val models: List<GeminiModelEntry> = emptyList())
@Serializable private data class GeminiModelEntry(
    val name: String? = null,
    val displayName: String? = null,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val supportedGenerationMethods: List<String>? = null,
)
