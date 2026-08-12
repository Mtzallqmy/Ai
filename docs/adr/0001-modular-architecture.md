# ADR 0001: Modular Architecture for Aegis AI Agent OS

## Status
Accepted

## Context
The project requires a production-level Android AI Agent platform that is extensible, secure, and reliable. A monolithic approach would make it difficult to support multiple AI providers, tools, and sandboxes.

## Decision
We adopt a **Multi-module Architecture** using Gradle Kotlin DSL.
The core layers are:
1. **Agent Core**: Manages the state machine and planning loop.
2. **Capability Registry**: A discovery layer for system features.
3. **Tool Runtime**: A typed execution environment for tools.
4. **Platform Adapters**: Android-specific implementations (Accessibility, Filesystem).

## Alternatives
- **Monolithic App**: Easier to start but fails on extensibility.
- **Microservices (Remote)**: Good for scaling but violates "local-first" and "privacy" requirements.

## Tradeoffs
- **Complexity**: Requires more boilerplate for module communication.
- **Build Time**: Initial build might be slower, but incremental builds are faster.
- **Decoupling**: Ensures that AI providers can be swapped without changing the core logic.
