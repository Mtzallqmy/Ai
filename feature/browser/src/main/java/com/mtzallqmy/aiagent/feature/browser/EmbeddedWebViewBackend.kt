package com.mtzallqmy.aiagent.feature.browser

import android.net.Uri
import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** Local WebView backend with explicit tab ownership and bounded operations. */
class EmbeddedWebViewBackend(
    private val engineFactory: () -> WebViewEngine? = { null },
    private val timeoutMs: Long = 30_000L,
) : BrowserBackend {
    override val id: String = "embedded_webview"
    override val name: String = "Embedded WebView"
    override val capabilities: Set<CapabilityId> = setOf(
        "browser.open",
        "browser.tabs",
        "browser.navigate",
        "browser.read",
        "browser.find",
        "browser.click",
        "browser.type",
        "browser.forms",
        "browser.scroll",
        "browser.evaluate",
        "browser.upload",
        "browser.download",
        "browser.cookies",
    ).mapTo(mutableSetOf(), ::CapabilityId)

    private val lock = Mutex()
    private val engines = linkedMapOf<String, WebViewEngine>()
    private var activeTabId: String? = null

    override suspend fun isAvailable(): Boolean = runCatching { engineFactory() != null }.getOrDefault(false)

    override suspend fun open(url: String): BrowserTab? = withTimeout(timeoutMs) {
        val engine = engineFactory() ?: return@withTimeout null
        engine.create()
        if (engine.navigate(url).isFailure) {
            engine.destroy()
            return@withTimeout null
        }
        val tabId = UUID.randomUUID().toString()
        lock.withLock {
            engines[tabId] = engine
            activeTabId = tabId
        }
        engine.toTab(tabId, active = true)
    }

    override suspend fun tabs(): List<BrowserTab> {
        val (active, snapshot) = lock.withLock { activeTabId to engines.toMap() }
        return snapshot.map { (id, engine) -> engine.toTab(id, id == active) }
    }

    override suspend fun activate(tabId: String): Boolean = lock.withLock {
        if (tabId !in engines) false else {
            activeTabId = tabId
            true
        }
    }

    override suspend fun navigate(url: String): Boolean = withEngine { it.navigate(url).isSuccess }

    override suspend fun currentState(): BrowserState {
        val (tabId, engine) = activeEngine() ?: return EMPTY_STATE
        return withTimeout(timeoutMs) {
            BrowserState(
                tabId = tabId,
                url = engine.currentUrl(),
                title = engine.title(),
                accessibleTree = engine.snapshot(),
            )
        }
    }

    override suspend fun find(query: String): Int = withEngineValue(0) { it.findOnPage(query) }

    override suspend fun click(selector: String): Boolean = action { it.clickSelector(selector) }

    override suspend fun type(selector: String, text: String): Boolean = action {
        it.typeIntoSelector(selector, text)
    }

    override suspend fun submitForm(selector: String?): Boolean = action { it.submitForm(selector) }

    override suspend fun scroll(deltaY: Int): Boolean = action { it.scrollBy(deltaY) }

    override suspend fun evaluate(script: String): JsonElement? = withEngineValue(null) { it.evaluate(script) }

    override suspend fun upload(selector: String, files: List<Uri>): Boolean =
        withEngine { it.upload(selector, files) }

    override suspend fun download(selector: String): BrowserArtifact? =
        withEngineValue(null) { it.download(selector) }

    override suspend fun cookies(): List<BrowserCookie> = withEngineValue(emptyList()) { it.cookies() }

    override suspend fun setCookie(cookie: BrowserCookie): Boolean = withEngine { it.setCookie(cookie) }

    override suspend fun clearCookies(): Boolean = withEngine { it.clearCookies() }

    override suspend fun close(tabId: String?): Boolean {
        val engine = lock.withLock {
            val target = tabId ?: activeTabId ?: return false
            engines.remove(target).also {
                if (activeTabId == target) activeTabId = engines.keys.lastOrNull()
            }
        } ?: return false
        withTimeout(timeoutMs) { engine.destroy() }
        return true
    }

    override suspend fun verify(expected: BrowserExpectation): BrowserVerification =
        verifyState(currentState(), expected)

    private suspend fun action(block: suspend (WebViewEngine) -> Boolean): Boolean = withEngine { engine ->
        val succeeded = block(engine)
        if (succeeded) engine.awaitIdle(timeoutMs.coerceAtMost(15_000L))
        succeeded
    }

    private suspend fun withEngine(block: suspend (WebViewEngine) -> Boolean): Boolean =
        withEngineValue(false, block)

    private suspend fun <T> withEngineValue(fallback: T, block: suspend (WebViewEngine) -> T): T {
        val engine = activeEngine()?.second ?: return fallback
        return runCatching { withTimeout(timeoutMs) { block(engine) } }.getOrDefault(fallback)
    }

    private suspend fun activeEngine(): Pair<String, WebViewEngine>? = lock.withLock {
        val id = activeTabId ?: return@withLock null
        engines[id]?.let { id to it }
    }

    private suspend fun WebViewEngine.toTab(id: String, active: Boolean) = BrowserTab(
        id = id,
        url = currentUrl(),
        title = title(),
        active = active,
    )

    private companion object {
        val EMPTY_STATE = BrowserState("", "", "", "")
    }
}
