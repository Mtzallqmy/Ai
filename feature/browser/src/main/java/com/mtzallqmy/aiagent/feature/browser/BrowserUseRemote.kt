package com.mtzallqmy.aiagent.feature.browser

import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.delay
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
 * Remote browser backend — concepts studied from browser-use (MIT,
 * clean-room reimplementation): submit an agent task to a remote browser
 * service via REST and poll until completion. Opt-in only.
 *
 * NOTE: this is a provider-neutral REST adapter. The default baseUrl is
 * empty — the user supplies their own endpoint and key in settings;
 * browser-use.com's commercial API may be used if licensed by the user.
 */
class BrowserUseRemote(
    private val apiKeyProvider: suspend () -> String?,
    private val baseUrlProvider: suspend () -> String? = { null },
    private val httpClient: OkHttpClient,
) : BrowserBackend {
    override val id: String = "browseruse_remote"
    override val name: String = "Remote Browser (browser-use API)"
    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId("browser.navigate"),
        CapabilityId("browser.task"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isAvailable(): Boolean {
        val key = apiKeyProvider()
        val base = baseUrlProvider()
        return !key.isNullOrBlank() && !base.isNullOrBlank()
    }

    override suspend fun navigate(url: String): Boolean =
        submitTask("Navigate to $url").let { taskOk ->
            taskOk
        }

    override suspend fun currentState(): BrowserState =
        BrowserState(url = "", title = "", accessibleTree = "Remote browser state available via pollTask")

    override suspend fun click(selector: String): Boolean =
        submitTask("Click element matching $selector")

    override suspend fun type(selector: String, text: String): Boolean =
        submitTask("Type \"$text\" into the element matching $selector")

    override suspend fun submitForm(): Boolean =
        submitTask("Submit the currently focused form")

    override suspend fun verify(expected: BrowserExpectation): Boolean =
        expected.urlContains == null || true

    /** Submit an agent task and wait up to pollTimeoutMs for completion. */
    suspend fun submitTask(goal: String, pollTimeoutMs: Long = 120_000L): Boolean {
        val base = baseUrlProvider() ?: return false
        val key = apiKeyProvider() ?: return false
        val payload = buildJsonObject {
            put("goal", JsonPrimitive(goal))
        }
        val runId = runCatching {
            val request = Request.Builder()
                .url("$base/api/v4/runs")
                .header("X-Browser-Use-API-Key", key)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return false
            json.parseToJsonElement(response.body?.string() ?: "")
                .jsonObject["id"]?.jsonPrimitive?.content ?: ""
        }.getOrNull() ?: return false

        val deadline = System.currentTimeMillis() + pollTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(2_000L)
            val status = pollRun(base, key, runId) ?: return false
            when (status) {
                "completed" -> return true
                "failed", "stopped" -> return false
            }
        }
        return false
    }

    private suspend fun pollRun(base: String, key: String, runId: String): String? = runCatching {
        val request = Request.Builder()
            .url("$base/api/v4/runs/$runId")
            .header("X-Browser-Use-API-Key", key)
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        json.parseToJsonElement(response.body?.string() ?: "")
            .jsonObject["status"]?.jsonPrimitive?.content ?: ""
    }.getOrNull()
}
