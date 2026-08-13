package com.mtzallqmy.aiagent.feature.browser

import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.serialization.json.JsonObject

/**
 * BrowserBackend abstraction — concepts studied from browser-use (MIT,
 * clean-room reimplementation): a task-driven browser interface where the
 * agent navigates, interacts, and *verifies* after every action.
 *
 * Strategy selection:
 *   BrowserBackend → EmbeddedWebView / AccessibilityBrowser / BrowserUseRemote
 *
 * Remote backends (browser-use cloud, etc.) are optional; the app never
 * requires Playwright/Chromium inside the APK.
 */
interface BrowserBackend {
    val id: String
    val name: String
    val capabilities: Set<CapabilityId>

    suspend fun isAvailable(): Boolean

    suspend fun navigate(url: String): Boolean

    suspend fun currentState(): BrowserState

    suspend fun click(selector: String): Boolean

    suspend fun type(selector: String, text: String): Boolean

    suspend fun submitForm(): Boolean

    /** Verification after an action (browser-use concept): confirm the page
     *  actually reached the expected condition. */
    suspend fun verify(expected: BrowserExpectation): Boolean
}

data class BrowserState(
    val url: String,
    val title: String,
    val accessibleTree: String,
    val lastActionOk: Boolean = true,
)

data class BrowserExpectation(
    val urlContains: String? = null,
    val titleContains: String? = null,
    val elementPresent: String? = null,
)

/** Result envelope for a remote browser task. */
data class RemoteBrowserTask(
    val taskId: String,
    val status: String, // pending | running | completed | failed
    val finalOutput: String? = null,
    val artifacts: Map<String, String> = emptyMap(),
)
