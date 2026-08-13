package com.mtzallqmy.aiagent.feature.browser

import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Embedded WebView backend. All webpage content is untrusted. */
class EmbeddedWebViewBackend(
    private val engineProvider: () -> WebViewEngine? = { null },
    private val timeoutMs: Long = 30_000L,
) : BrowserBackend {
    override val id: String = "embedded_webview"
    override val name: String = "Embedded WebView"
    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId("browser.navigate"),
        CapabilityId("browser.read"),
        CapabilityId("browser.click"),
        CapabilityId("browser.type"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isAvailable(): Boolean = engineProvider() != null

    override suspend fun navigate(url: String): Boolean {
        if (!BrowserSecurityPolicy.isAllowedUrl(url)) return false
        return runEngine { engine -> engine.navigate(url).isSuccess } ?: false
    }

    override suspend fun currentState(): BrowserState = runEngineState(::snapshotState)
        ?: BrowserState("", "", "")

    override suspend fun click(selector: String): Boolean {
        if (!BrowserSecurityPolicy.isSafeSelector(selector)) return false
        return runEngine { engine -> engine.clickSelector(selector) } ?: false
    }

    override suspend fun type(selector: String, text: String): Boolean {
        if (!BrowserSecurityPolicy.isSafeSelector(selector) || !BrowserSecurityPolicy.isSafeText(text)) return false
        return runEngine { engine -> engine.typeIntoSelector(selector, text) } ?: false
    }

    override suspend fun submitForm(): Boolean = runEngine { engine ->
        val submitted = engine.typeIntoSelector("input:focus, textarea:focus", "\n")
        submitted && engine.snapshot().isNotEmpty()
    } ?: false

    override suspend fun verify(expected: BrowserExpectation): Boolean = runEngine { engine ->
        val state = snapshotState(engine)
        (expected.urlContains == null || state.url.contains(expected.urlContains, ignoreCase = true)) &&
            (expected.titleContains == null || state.title.contains(expected.titleContains, ignoreCase = true)) &&
            (expected.elementPresent == null || expected.elementPresent in state.accessibleTree)
    } ?: false

    private suspend fun snapshotState(engine: WebViewEngine): BrowserState {
        val sanitized = BrowserSnapshotSanitizer.sanitize(engine.snapshot())
        val root = json.parseToJsonElement(sanitized).jsonObject
        val title = root["title"]?.jsonPrimitive?.content.orEmpty()
        return BrowserState(
            url = engine.currentUrl() ?: root["url"]?.jsonPrimitive?.content.orEmpty(),
            title = title,
            accessibleTree = sanitized,
        )
    }

    private suspend fun runEngine(block: suspend (WebViewEngine) -> Boolean): Boolean? = runCatching {
        val engine = engineProvider() ?: return null
        withContext(Dispatchers.Main) {
            withTimeout(timeoutMs) {
                block(engine).also { delay(400) }
            }
        }
    }.getOrNull()

    private suspend fun runEngineState(block: suspend (WebViewEngine) -> BrowserState): BrowserState? = runCatching {
        withContext(Dispatchers.Main) {
            val engine = engineProvider() ?: return@withContext null
            withTimeout(timeoutMs) { block(engine) }
        }
    }.getOrNull()
}
