# Final Production Readiness

This document is a release gate, not a marketing checklist. A box may be checked only from reproducible CI output, an attached test artifact, or a recorded device/interoperability run. Do not tag or publish a release while any required gate is open.

## Automated CI gates

- [ ] Full-history secret scan on final release commit
- [ ] Dependency/security scan
- [ ] License scan
- [ ] Rust host tests
- [ ] Android lint
- [ ] Kotlin/JVM unit and integration tests
- [ ] Native local-LLM unit/build checks
- [ ] x86_64 emulator instrumentation
- [ ] Debug APK build
- [ ] Release APK build
- [ ] Release AAB build

## Android native runtime evidence

- [x] Kotlin -> Binder -> isolatedProcess -> JNI -> Rust -> child-process instrumentation exists
- [x] Valid command, invalid executable/environment, timeout, cancellation, bounded stdout/stderr and concurrency cases exist
- [x] App-private file isolation canary exists
- [ ] Binder death recovery
- [ ] Isolated process crash recovery
- [ ] App restart/reconnect
- [ ] Provider/OAuth/SSH/database secret canaries
- [ ] Descriptor lifecycle stress run

## Local LLM

- [x] GGUF discovery and metadata reader tests
- [x] Checksum mismatch and RAM/OOM preflight tests
- [x] Unsupported ABI and model-root confinement tests
- [ ] Valid GGUF load/generate/cancel/unload/reload on Android hardware
- [ ] Corrupted/unsupported GGUF Android run
- [ ] Repeated inference and process recreation
- [ ] Low-memory device evidence
- [ ] Mid-range device evidence
- [ ] Flagship device evidence
- [ ] x86_64 emulator evidence where supported
- [ ] Measured tokens/sec and memory usage

## Routing, workflows and scheduling

- [x] Smart Router sensitive/offline/vision/coding/simple/long-context/disabled unit cases
- [x] Workflow validation/retry/timeout/cancel/pause/resume/parallel/persistence unit cases
- [x] Workflow synthetic recovery with stable idempotency key
- [ ] Workflow Android process-kill/restart recovery
- [x] Schedule exact eligibility/inexact/cancel/constraint semantics unit cases
- [ ] Schedule reboot/package-update restoration device evidence
- [ ] Exact/inexact/deferred/waiting UX verification

## Delegation and interoperability

- [x] Sub-agent tool/capability isolation and budgets
- [x] Sub-agent cancellation/timeout/concurrency/failure/result-handoff tests
- [x] MCP ToolRuntime approval-path test
- [x] MCP malformed response/auth rejection/reconnect/PKCE/state/expired-token unit tests
- [ ] Multiple real MCP server interoperability runs
- [ ] Real OAuth PKCE token exchange and denied-scope/expired-token runs

## Device, vision, browser and SSH

- [ ] Android Settings -> Bluetooth Device Agent E2E
- [ ] Tap/long-press/type/clear/scroll/swipe/back/home/open-app/open-URI E2E
- [ ] Accessibility disabled/permission denied/stale node/retry E2E
- [ ] Accessibility + screenshot + optional vision fusion E2E
- [x] Browser URL/selector/text untrusted-input policy tests
- [x] Browser form-value snapshot sanitization test
- [ ] Embedded WebView open/snapshot/find/type/click/verify E2E
- [ ] Browser redirect/download/upload/back/forward/timeout cases
- [x] SSH strict host-key verification is default and trust-all policy is rejected
- [ ] Real SSH known-host/fingerprint/password/key/passphrase/PTY/stream/cancel matrix

## Memory, database and UI

- [x] Typed memory TTL/scoring/recency/importance/dedup/pinning/edit/delete/namespace behavior exists
- [x] RAG chunk/embed/store/retrieve and secret-rejection unit tests
- [ ] Local embedding document-to-citation E2E
- [ ] Cloud embedding document-to-citation E2E
- [x] Destructive Room migration fallback removed
- [x] Room schema export enabled
- [ ] Prior-release schema migration/upgrade test
- [ ] Persist ToolExecution, WorkflowRun, schedules, approval history and artifacts
- [x] Shared core design-system module/tokens introduced
- [x] Product chat item/state/event/route model introduced
- [x] Local Models screen/state/event/route introduced
- [ ] Design system consumed by every product screen
- [ ] One Screen/ViewModel/UiState/UiEvent/Route per required feature
- [ ] Product Chat rendering/actions/attachments/provider-model indicators
- [ ] Agent Run timeline UI without private chain-of-thought
- [ ] Full Security Center revoke/reset UX

## Performance and release engineering

- [ ] Cold/warm start measurements
- [ ] Compose recomposition measurements
- [ ] Chat streaming measurements
- [ ] Accessibility/screenshot/Room/vector/JNI/WebView/workflow measurements
- [ ] Baseline Profile
- [ ] Macrobenchmark
- [ ] Main branch required-status-check protection enabled
- [ ] Final readiness branch updated from current main
- [ ] Security audit with no unresolved critical/high issues
- [ ] Signed APK from protected signing secrets
- [ ] Signed AAB from protected signing secrets
- [ ] SHA-256 checksums
- [ ] Release notes/changelog/ABI/minSdk/native/local-LLM requirements

Release publication remains blocked until every required gate above is evidenced.
