# Aegis AI Agent OS

**Aegis AI Agent OS** is a production-grade, multi-provider AI agent platform for Android. It runs autonomous multi-step agent tasks (chat, file handling, HTTP calls, device actions, SSH, sandboxed execution, accessibility-driven UI automation) with real SSE streaming, human-in-the-loop approval for sensitive operations, and secure credential storage backed by the Android Keystore.

- **Package:** `com.mtzallqmy.aiagent`
- **Minimum:** Android 8.0 (API 26) · **Target:** Android 14 (API 34)
- **Build:** AGP 8.5.2 · Kotlin 1.9.24 · Jetpack Compose + Material 3 · 100% Kotlin (no Java, no C/C++, no NDK)
- **versionCode:** 64 · **versionName:** 1.0.0

![Build](https://github.com/Mtzallqmy/Ai/actions/workflows/build.yml/badge.svg)

## Features

| Area | What it does |
|---|---|
| Multi-provider AI | OpenAI, Anthropic (claude-opus-4-7), Google Gemini, OpenRouter, and any OpenAI-compatible endpoint |
| Real streaming | SSE streaming from every provider, normalized into `GenerationEvent` values for a provider-agnostic agent core |
| Agent runtime | Full state machine (IDLE → THINKING → PLANNING → EXECUTING_TOOL → WAITING_FOR_APPROVAL → COMPLETED/FAILED/CANCELLED) with multi-step tool calling and cancellation |
| Tools | File system, HTTP (with SSRF protection), clipboard, device info, SSH, terminal, browser (WebView), sandbox policy |
| Human approval | Sensitive actions (MODIFY / COMMUNICATION / FINANCIAL / SYSTEM_SENSITIVE risk levels) route through an approval UI with ALLOW / ASK_ONCE / ASK_EVERY_TIME / DENY policies |
| Security | Android Keystore credential vault, SSRF-safe HTTP client, secret sanitization in logs, scoped permission prompts |
| Automation | Android Accessibility Service drives UI automation on-device |
| Persistence | Room (KSP) for agent runs and messages; DataStore for settings |
| Localization | Arabic + English string resources |

## Architecture

The project is a multi-module Gradle build (37 modules) organized into five layers:

- `core/*` — model, common, network, security, database, datastore, agent runtime, tools, capabilities, memory, workspace, ui, permissions
- `feature/*` — chat, providers, settings, security center, logs, files, browser, terminal, sandbox, device
- `provider/*` — openai, anthropic, google, openrouter, openai-compatible
- `tool/*` — android, filesystem, terminal, http, mcp, clipboard, ssh
- `app` — composition root (`AegisApp`), MainActivity, navigation, bottom tabs

Dependency rule: feature/provider/tool modules never depend on `app`; the app module wires everything together with lightweight manual constructor injection (no Hilt/Dagger).

## Building

```bash
# Debug
./gradlew assembleDebug

# Release (set signing env vars first)
export RELEASE_KEYSTORE=/path/to/release.jks
export RELEASE_KEYSTORE_PASSWORD=...
export RELEASE_KEY_ALIAS=...
export RELEASE_KEY_PASSWORD=...
./gradlew assembleRelease
```

APK lands in `app/build/outputs/apk/release/app-release.apk` (signed, universal APK covering arm64-v8a).

## Testing

```bash
./gradlew test        # 25 local JUnit tests across core:common and core:agent
```

Instrumented tests are defined (app + androidx.test + Compose UI test stack) and run on a device/emulator with:

```bash
./gradlew connectedAndroidTest
```

## Configuration

API keys are never bundled. Users enter them in **Settings → Providers**, and they are stored in the Android Keystore via `CredentialVault`. A custom OpenAI-compatible provider can point at any base URL.

## License

Private — all rights reserved (Mtzallqmy).
