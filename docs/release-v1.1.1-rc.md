# Aegis AI Agent OS v1.1.1 — Release Candidate

This document is the release evidence manifest. A tag or public release must not be created while any required item below is unresolved.

## Hardening included

- Android lint is no longer permanently bypassed in CI.
- Full-history Gitleaks secret scanning remains enabled for pull requests.
- Rust runtime host tests run in CI.
- Rust Android instrumentation exercises Kotlin → Binder → isolated process → JNI → Rust → child process, including allowlists, timeout, cancellation, bounded output, concurrency, and app-private-file isolation.
- Sub-agent tests cover capability/tool isolation, budgets, cancellation, parent-child lifecycle, concurrency limits, child failure containment, and result handoff.
- Local LLM Android test dependencies are configured; valid-GGUF real-device evidence is still required.
- A shared Compose design-system module and product-facing Local Models / Agent Run screens have been staged.
- Room schema export is enabled and durable execution/audit entities have been defined. A non-destructive v1→v2 migration remains a mandatory release gate.

## Automated evidence

- [x] Full-history secret scan on current RC head.
- [x] Rust host tests on current RC CI lineage.
- [ ] Android lint on final release commit.
- [ ] JVM/unit test suite on final release commit.
- [ ] Debug APK build on final release commit.
- [ ] Android instrumentation on final release commit.
- [ ] Dependency vulnerability gate.
- [ ] License policy gate.
- [ ] Release APK build.
- [ ] Release AAB build.

## Device / integration evidence

- [ ] Rust Binder death / isolated-service crash / app-restart recovery.
- [ ] Valid GGUF load and inference on low-memory Android device.
- [ ] Valid GGUF load and inference on mid-range Android device.
- [ ] Valid GGUF load and inference on flagship Android device.
- [ ] Local-model OOM/preflight behavior and process recreation.
- [ ] Workflow recovery after actual Android process kill/restart.
- [ ] Scheduling behavior across exact/inexact alarms, Doze, reboot, and app update.
- [ ] Accessibility/ADB Device Backend end-to-end verification.
- [ ] Screenshot/vision end-to-end verification where supported.
- [ ] MCP interoperability with multiple real servers, including OAuth/auth failure/reconnect/malformed RPC.
- [ ] Browser WebView end-to-end verification.
- [ ] SSH real-server matrix for host verification, key/passphrase/auth, timeout/cancel, and bounded output.
- [ ] RAG document → embedding → retrieval → source/citation path with configured embedding provider.

## Data safety

- [ ] Remove destructive Room migration fallback.
- [ ] Provide and test non-destructive database migration from the published v1.1.0 schema.
- [ ] Migration test proves existing conversations, memory, provider configuration, workflows, and run history survive upgrade.

## UI / performance

- [ ] Wire Local Models and Agent Run screens into production navigation.
- [ ] Wire actionable Security Center using only public runtime APIs.
- [ ] Adopt shared design-system tokens throughout user-facing screens.
- [ ] Baseline Profile / Macrobenchmark evidence for startup and representative navigation.
- [ ] Profile memory/CPU for local inference and long-running agent execution.

## Release engineering

- [ ] Bump versionCode from 65 to 66 and versionName from 1.1.0 to 1.1.1.
- [ ] Verify signing secrets are configured in GitHub Actions without exposing values.
- [ ] Produce signed APK.
- [ ] Produce signed AAB.
- [ ] Generate SHA-256 checksums for release artifacts.
- [ ] Protect `main` and enforce required status checks before merge.
- [ ] Merge the release candidate only after all mandatory gates above are satisfied.
- [ ] Create tag `v1.1.1` from the verified merge commit.
- [ ] Publish release notes and verified artifacts from that tag.
