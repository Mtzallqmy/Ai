# Architecture — Aegis AI Agent OS

## Module Map

```
app                        ← composition root (AegisApp), MainActivity, tabs
 ├─ core:model             shared domain: AgentState, GenerationEvent, AiModel, ChatMessage,
 │                           ToolDescriptor, ModelCapabilities, RiskLevel, ProviderError...
 ├─ core:common            SecretSanitizer, typed errors, logging
 ├─ core:network           AiProvider interface, SafeHttpClient (OkHttp + SSRF protection)
 ├─ core:security          CredentialVault (Android Keystore), CredentialScope
 ├─ core:database          Room (AgentRunDao, ChatMessageDao), DatabaseProvider
 ├─ core:datastore         SecureSettings (DataStore)
 ├─ core:agent             AgentRuntime (state machine + tool loop), ProviderRegistry,
 │                           ContextManager, SkillRegistry
 ├─ core:tools             ToolRuntime, ApprovalEngine, AgentTool<T,R>
 ├─ core:capabilities      CapabilityRegistry, CapabilityId
 ├─ core:memory            MemoryStore (long-term memory over Room)
 ├─ core:workspace         WorkspaceManager (agent workspace directory)
 ├─ core:ui                shared Compose primitives
 ├─ core:permissions       permission request helpers
 ├─ feature:chat           main conversation screen
 ├─ feature:providers      provider list + per-provider config + connection test
 ├─ feature:settings       settings screen (keys, custom provider, policies)
 ├─ feature:security       SecurityCenter / SecurityReport (accessibility, notification listener,
 │                           keystore health, SSRF policy)
 ├─ feature:logs           run logs history
 ├─ feature:files          file browser
 ├─ feature:browser        WebView-based browser tool UI
 ├─ feature:terminal       on-device terminal output UI
 ├─ feature:sandbox        sandbox policy UI
 ├─ feature:device         device tools UI
 ├─ provider:openai        OpenAiProvider (SSE streaming)
 ├─ provider:anthropic     AnthropicProvider (claude-opus-4-7)
 ├─ provider:google        GeminiProvider
 ├─ provider:openrouter    OpenRouterProvider
 └─ provider:openai-compatible  OpenAiCompatibleProvider (custom base URL, custom auth header)

tool:android · tool:filesystem · tool:terminal · tool:http · tool:mcp · tool:clipboard · tool:ssh
    ← concrete AgentTool implementations registered with ToolRuntime
```

## Dependency Rule

`core` ← `feature` / `provider` / `tool` ← `app`. Nothing downstream may depend on `app`. The `AiProvider` interface intentionally lives in `core:network` so providers (which need network) and the agent runtime (which consumes providers) never form a cycle.

## Data Flow — one agent turn

1. User message enters `feature:chat` → `AgentRuntime.submit(...)`.
2. Runtime transitions IDLE → THINKING → PLANNING, builds a `GenerationRequest` (messages + tool descriptors).
3. The selected provider streams normalized `GenerationEvent` values (SSE under the hood).
4. On a tool call, the runtime transitions EXECUTING_TOOL and asks `ToolRuntime` to execute.
5. `ApprovalEngine` checks the tool's `RiskLevel`; anything above READ may be routed to the approval UI (`WAITING_FOR_APPROVAL`).
6. Tool result becomes an observation (OBSERVING), the loop continues until COMPLETED/FAILED/CANCELLED.
7. Results are persisted via Room and surfaced in `feature:logs`.

## Concurrency

- Kotlin Coroutines + Flow everywhere; no RxJava.
- Providers stream through `kotlinx.coroutines.flow.Flow<GenerationEvent>`.
- `rememberCoroutineScope` drives UI-triggered actions (e.g. connection tests).

## DI

Manual constructor injection via `AegisApp` (a lightweight composition root). Registries (`ProviderRegistry`, `CapabilityRegistry`, `SkillRegistry`) hold pluggable implementations; no reflection-based framework.

## Build System

- AGP 8.5.2, Gradle 8.7, KSP 1.9.24-1.0.20, JDK 21.
- Each library module publishes a single debug variant (`singleVariant("debug")`) to avoid variant ambiguity on the app classpath.
- Release signing is driven by environment variables (`RELEASE_KEYSTORE` family) so no keystore is committed.
