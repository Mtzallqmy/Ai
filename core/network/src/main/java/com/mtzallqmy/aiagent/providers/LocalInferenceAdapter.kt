package com.mtzallqmy.aiagent.providers

/**
 * Local / on-device inference abstraction — concept studied from Kai 9000's
 * LiteRT on-device inference (Apache-2.0, clean-room reimplementation).
 *
 * Aegis ships NO native inference binaries (no llama.cpp / no NDK). Instead
 * this interface lets any on-device runtime (e.g., a LiteRT-based host app
 * module) plug in as just another "provider" with an OpenAI-compatible surface.
 * The adapter is provider-neutral and can be disabled without breaking core.
 *
 * Usage: wrap a local runtime URL (local HTTP server) with OpenAiCompatibleProvider,
 * or implement LocalInferenceRuntime for a pure-Kotlin embeddable runtime.
 */
interface LocalInferenceRuntime {
    val isSupported: Boolean
    val availableModels: List<String>
    /** Health probe; null if the runtime is unavailable. */
    suspend fun healthCheck(): Boolean
}

/** No-op runtime — local inference is opt-in and never required. */
object DisabledLocalInference : LocalInferenceRuntime {
    override val isSupported = false
    override val availableModels = emptyList<String>()
    override suspend fun healthCheck() = false
}
