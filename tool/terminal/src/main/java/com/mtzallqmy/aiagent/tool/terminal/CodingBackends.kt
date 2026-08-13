package com.mtzallqmy.aiagent.tool.terminal

import com.mtzallqmy.aiagent.agent.backends.CodingBackend
import com.mtzallqmy.aiagent.agent.backends.CodingResult
import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * On-device coding backend. Each approved step is parsed as direct argv by
 * TerminalToolSet and executed through the isolated Rust runtime; no app-process
 * shell or command-chain interpretation is used.
 */
class LocalSandboxCoding(
    private val terminal: TerminalToolSet = TerminalToolSet(),
) : CodingBackend {
    override val id: String = "local_sandbox"
    override val name: String = "Local Isolated Commands"
    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId("coding.run"),
        CapabilityId("coding.lint"),
        CapabilityId("coding.patch"),
    )

    override suspend fun isAvailable(): Boolean = runCatching {
        terminal.executeCommand("echo ok").let { it.exitCode == 0 && it.stdout.trim() == "ok" }
    }.getOrDefault(false)

    override suspend fun run(task: String, context: Map<String, String>): CodingResult = withContext(Dispatchers.IO) {
        val steps = context["steps_json"]
            ?.let {
                runCatching {
                    Json.parseToJsonElement(it).jsonObject["steps"]
                        ?.let { value ->
                            (value as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { item -> (item as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                ?.filter { command -> command.isNotBlank() }
                        }
                }.getOrNull()
            }
            ?: emptyList()

        if (steps.isEmpty()) {
            return@withContext CodingResult(
                success = false,
                summary = "No execution steps provided",
                errors = listOf("Pass 'steps_json' with an array of allow-listed direct commands"),
            )
        }

        val outputs = mutableListOf<String>()
        var failed = false
        for (step in steps) {
            val result = terminal.executeCommand(step)
            outputs += "> $step\n${result.stdout}${result.stderr}"
            if (result.exitCode != 0) {
                failed = true
                break
            }
        }
        CodingResult(
            success = !failed,
            summary = if (!failed) "All ${steps.size} step(s) completed" else "Stopped at a failing step",
            patches = emptyList(),
            errors = if (failed) listOf(outputs.last()) else emptyList(),
            artifacts = if (outputs.isNotEmpty()) mapOf("log" to outputs.joinToString("\n---\n").take(40_000)) else emptyMap(),
        )
    }
}

/** Remote OpenHands adapter. Requires an explicitly configured self-hosted endpoint and token. */
class OpenHandsRemote(
    private val baseUrlProvider: suspend () -> String?,
    private val tokenProvider: suspend () -> String?,
    private val httpClient: OkHttpClient,
) : CodingBackend {
    override val id: String = "openhands_remote"
    override val name: String = "OpenHands (remote)"
    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId("coding.run"),
        CapabilityId("coding.patch"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isAvailable(): Boolean {
        val base = baseUrlProvider()
        val token = tokenProvider()
        return !base.isNullOrBlank() && !token.isNullOrBlank()
    }

    override suspend fun run(task: String, context: Map<String, String>): CodingResult {
        val base = baseUrlProvider() ?: return CodingResult(false, "OpenHands base URL not configured")
        val token = tokenProvider() ?: return CodingResult(false, "OpenHands token not configured")

        val payload = buildJsonObject {
            put("goal", JsonPrimitive(task))
            context["repo"]?.let { put("repo", JsonPrimitive(it)) }
            context["files"]?.let { put("files", JsonPrimitive(it)) }
        }

        val runId = runCatching {
            val request = Request.Builder()
                .url("$base/api/runs")
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return CodingResult(false, "OpenHands rejected the run (${response.code})")
            json.parseToJsonElement(response.body?.string() ?: "")
                .jsonObject["run_id"]?.jsonPrimitive?.content ?: ""
        }.getOrElse { return CodingResult(false, it.message ?: "OpenHands request failed") }

        val deadline = System.currentTimeMillis() + 10L * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(3_000L)
            val status = pollStatus(base, token, runId) ?: return CodingResult(false, "OpenHands polling failed")
            when (status) {
                "finished" -> return CodingResult(true, "OpenHands run completed: $runId", artifacts = mapOf("run_id" to runId))
                "error", "stopped" -> return CodingResult(false, "OpenHands run ended with status: $status")
            }
        }
        return CodingResult(false, "OpenHands run timed out")
    }

    private suspend fun pollStatus(base: String, token: String, runId: String): String? = runCatching {
        val request = Request.Builder()
            .url("$base/api/runs/$runId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        json.parseToJsonElement(response.body?.string() ?: "")
            .jsonObject["status"]?.jsonPrimitive?.content ?: ""
    }.getOrNull()
}
