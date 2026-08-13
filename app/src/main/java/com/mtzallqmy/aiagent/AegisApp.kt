package com.mtzallqmy.aiagent

import android.app.Application
import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.agent.ContextManager
import com.mtzallqmy.aiagent.agent.GraphAgentEngine
import com.mtzallqmy.aiagent.agent.HeartbeatAgent
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.agent.SkillRegistry
import com.mtzallqmy.aiagent.agent.backends.DeviceBackend
import com.mtzallqmy.aiagent.agent.backends.DeviceBackendRegistry
import com.mtzallqmy.aiagent.agent.backends.CodingBackend
import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.database.DatabaseProvider
import com.mtzallqmy.aiagent.feature.device.DeviceToolSet
import com.mtzallqmy.aiagent.memory.DocumentIngestor
import com.mtzallqmy.aiagent.memory.InMemoryVectorStore
import com.mtzallqmy.aiagent.memory.KeywordEmbedder
import com.mtzallqmy.aiagent.memory.MemoryRefiner
import com.mtzallqmy.aiagent.memory.MemoryStore
import com.mtzallqmy.aiagent.provider.anthropic.AnthropicProvider
import com.mtzallqmy.aiagent.provider.compatible.OpenAiCompatibleProvider
import com.mtzallqmy.aiagent.provider.google.GeminiProvider
import com.mtzallqmy.aiagent.provider.openai.OpenAiProvider
import com.mtzallqmy.aiagent.provider.openrouter.OpenRouterProvider
import com.mtzallqmy.aiagent.security.CredentialScope
import com.mtzallqmy.aiagent.security.CredentialVault
import com.mtzallqmy.aiagent.tool.clipboard.ClipboardToolSet
import com.mtzallqmy.aiagent.tool.filesystem.FileToolSet
import com.mtzallqmy.aiagent.tool.http.HttpToolSet
import com.mtzallqmy.aiagent.tool.ssh.SshToolSet
import com.mtzallqmy.aiagent.tool.terminal.TerminalToolSet
import com.mtzallqmy.aiagent.tools.ApprovalEngine
import com.mtzallqmy.aiagent.tools.SharedPreferencesApprovalRuleStore
import com.mtzallqmy.aiagent.tools.ToolRuntime
import com.mtzallqmy.aiagent.tools.TypedToolRegistry
import com.mtzallqmy.aiagent.workspace.WorkspaceManager

/**
 * Minimal composition root: wires all registered implementations.
 * No hardcoded keys — credentials only enter at runtime via Settings/CredentialVault.
 */
class AegisApp : Application() {

    lateinit var databaseProvider: DatabaseProvider
        private set
    lateinit var settings: SecureSettings
        private set
    lateinit var vault: CredentialVault
        private set
    lateinit var capabilityRegistry: CapabilityRegistry
        private set
    lateinit var approvalEngine: ApprovalEngine
        private set
    lateinit var toolRuntime: ToolRuntime
        private set
    lateinit var toolRegistry: TypedToolRegistry
        private set
    lateinit var providerRegistry: ProviderRegistry
        private set
    lateinit var runtime: AgentRuntime
        private set
    lateinit var contextManager: ContextManager
        private set
    lateinit var memoryStore: MemoryStore
        private set
    lateinit var workspaceManager: WorkspaceManager
        private set
    lateinit var skillRegistry: SkillRegistry
        private set
    lateinit var deviceBackendRegistry: DeviceBackendRegistry
        private set
    lateinit var heartbeatAgent: HeartbeatAgent
        private set
    lateinit var memoryRefiner: MemoryRefiner
        private set
    lateinit var documentIngestor: DocumentIngestor
        private set
    lateinit var codingBackend: CodingBackend
        private set
    lateinit var graphAgentEngine: GraphAgentEngine<Any>
        private set

    override fun onCreate() {
        super.onCreate()

        databaseProvider = DatabaseProvider
        settings = SecureSettings(this)
        vault = CredentialVault(this)

        capabilityRegistry = CapabilityRegistry()
        approvalEngine = ApprovalEngine(ruleStore = SharedPreferencesApprovalRuleStore(this))
        toolRuntime = ToolRuntime(capabilityRegistry, approvalEngine)

        providerRegistry = ProviderRegistry()
        // Keys are resolved lazily from the CredentialVault (Android Keystore). No secret ever
        // lives in these lambdas or in memory beyond the loaded value.
        providerRegistry.register(
            OpenAiProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "openai_api_key") }),
        )
        providerRegistry.register(
            AnthropicProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "anthropic_api_key") }),
        )
        providerRegistry.register(
            GeminiProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "gemini_api_key") }),
        )
        providerRegistry.register(
            OpenRouterProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "openrouter_api_key") }),
        )
        providerRegistry.register(
            OpenAiCompatibleProvider(
                baseUrlProvider = { settings.getString("custom_provider_base_url") ?: "" },
                apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "custom_provider_api_key") },
            ),
        )

        contextManager = ContextManager()
        memoryStore = MemoryStore { databaseProvider.get(this) }
        workspaceManager = WorkspaceManager(this)
        skillRegistry = SkillRegistry()

        toolRegistry = TypedToolRegistry().apply {
            (
                FileToolSet(this@AegisApp).tools +
                    HttpToolSet().tools +
                    ClipboardToolSet(this@AegisApp).tools +
                    DeviceToolSet(this@AegisApp).tools +
                    TerminalToolSet().tools +
                    SshToolSet().tools
                ).forEach(::register)
        }

        // Phase 5 integrations (studied reference repos, clean-room implementations)
        val vectorStore = InMemoryVectorStore()
        val embedder = KeywordEmbedder()
        memoryRefiner = MemoryRefiner()
        documentIngestor = DocumentIngestor(vectorStore, embedder)
        heartbeatAgent = HeartbeatAgent()

        // Device backends: Accessibility (on-device) + ADB (optional, requires PC pairing)
        deviceBackendRegistry = DeviceBackendRegistry()
        deviceBackendRegistry.register(com.mtzallqmy.aiagent.tool.android.AccessibilityDeviceBackend())
        deviceBackendRegistry.register(com.mtzallqmy.aiagent.tool.android.AdbDeviceBackend())

        codingBackend = com.mtzallqmy.aiagent.tool.terminal.LocalSandboxCoding()

        // Minimal proof-of-concept graph: plan -> execute -> review with interrupt at review
        graphAgentEngine = GraphAgentEngine(entryNode = "plan") { nodeId, state ->
            when (nodeId) {
                "plan" -> GraphAgentEngine.GraphNextStep.Goto("execute", state)
                "execute" -> GraphAgentEngine.GraphNextStep.Goto("review", state)
                else -> GraphAgentEngine.GraphNextStep.End(state)
            }
        }
        graphAgentEngine.interruptBefore = setOf("review")

        runtime = AgentRuntime(
            provider = providerRegistry.select("openai"),
            toolRuntime = toolRuntime,
        )
    }
}
