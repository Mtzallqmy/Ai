package com.mtzallqmy.aiagent.providers

import com.mtzallqmy.aiagent.agent.AiProvider
import com.mtzallqmy.aiagent.agent.GenerationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import java.io.IOException

class OpenAiProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1"
) : AiProvider {

    override val providerId: String = "openai"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(): Result<List<String>> {
        // Implementation for listing models from /v1/models
        return Result.success(listOf("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo"))
    }

    override suspend fun generate(prompt: String, model: String): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.GenerationStarted)

        val requestBody = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(GenerationEvent.GenerationFailed(IOException("Unexpected code $response")))
                    return@flow
                }

                val reader = response.body?.source()?.inputStream()?.bufferedReader()
                reader?.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        if (data == "[DONE]") {
                            emit(GenerationEvent.GenerationCompleted(""))
                            return@forEachLine
                        }

                        try {
                            val element = json.parseToJsonElement(data).jsonObject
                            val choices = element["choices"]?.jsonArray
                            if (choices != null && choices.isNotEmpty()) {
                                val delta = choices[0].jsonObject["delta"]?.jsonObject
                                
                                // Handle Text Delta
                                delta?.get("content")?.jsonPrimitive?.content?.let { text ->
                                    emit(GenerationEvent.TextDelta(text))
                                }

                                // Handle Tool Calls (Placeholder for complex logic)
                                delta?.get("tool_calls")?.jsonArray?.let { tools ->
                                    emit(GenerationEvent.ToolCallStarted(tools.toString()))
                                }
                            }
                            
                            // Handle Usage
                            element["usage"]?.jsonObject?.let { usage ->
                                val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.int ?: 0
                                val completionTokens = usage["completion_tokens"]?.jsonPrimitive?.int ?: 0
                                emit(GenerationEvent.Usage(promptTokens, completionTokens))
                            }
                        } catch (e: Exception) {
                            // Skip malformed lines
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit(GenerationEvent.GenerationFailed(e))
        }
    }.flowOn(Dispatchers.IO)
}
