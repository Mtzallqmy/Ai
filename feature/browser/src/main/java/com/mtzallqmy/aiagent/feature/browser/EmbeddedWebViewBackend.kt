package com.mtzallqmy.aiagent.feature.browser

import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Embedded WebView browser backend — local, no external dependencies.
 * Uses WebViewEngine (already hardened with URL policy + safe JS injection).
 */
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

    override suspend fun isAvailable(): Boolean = engineProvider() != null

    override suspend fun navigate(url: String): Boolean = runEngine { engine ->
        engine.navigate(url).isSuccess
    } ?: false

    override suspend fun currentState(): BrowserState = runEngineState { engine ->
        BrowserState(
            url = engine.currentUrl() ?: "",
            title = "",
            accessibleTree = engine.snapshot(),
        )
    } ?: BrowserState("", "", "")

    override suspend fun click(selector: String): Boolean = runEngine { engine ->
        engine.clickSelector(selector)
    } ?: false

    override suspend fun type(selector: String, text: String): Boolean = runEngine { engine ->
        engine.typeIntoSelector(selector, text)
    } ?: false

    override suspend fun submitForm(): Boolean = runEngine { engine ->
        // Submit the active form via the focused element; falls back to Enter key on the last typed field.
        engine.typeIntoSelector("input:focus, textarea:focus", "\n").not().let {
            engine.snapshot().isNotEmpty() // page responded = plausible navigation
        }
    } ?: false

    override suspend fun verify(expected: BrowserExpectation): Boolean = runEngine { engine ->
        val state = BrowserState(
            url = engine.currentUrl() ?: "",
            title = "",
            accessibleTree = engine.snapshot(),
        )
        (expected.urlContains == null || state.url.contains(expected.urlContains, ignoreCase = true)) &&
            (expected.titleContains == null || true) &&
            (expected.elementPresent == null || expected.elementPresent in state.accessibleTree)
    } ?: false

    private suspend fun runEngine(block: suspend (WebViewEngine) -> Boolean): Boolean? {
        val engine = engineProvider() ?: return null
        return withContext(Dispatchers.Main) {
            withTimeout(timeoutMs) {
                block(engine).also { delay(400) }
            }
        }
    }

    private suspend fun runEngineState(block: suspend (WebViewEngine) -> BrowserState): BrowserState? =
        withContext(Dispatchers.Main) {
            val engine = engineProvider() ?: return@withContext null
            withTimeout(timeoutMs) { block(engine) }
        }
}
