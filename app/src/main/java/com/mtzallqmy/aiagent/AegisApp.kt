package com.mtzallqmy.aiagent

import android.app.Application
import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.agent.ContextManager
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.agent.SkillRegistry
import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.database.DatabaseProvider
import com.mtzallqmy.aiagent.feature.device.DeviceToolSet
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
import com.mtzallqmy.aiagent.tools.ApprovalEngine
import com.mtzallqmy.aiagent.tools.ToolRuntime
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

    override fun onCreate() {
        super.onCreate()

        databaseProvider = DatabaseProvider
        settings = SecureSettings(this)
        vault = CredentialVault(this)

        capabilityRegistry = CapabilityRegistry()
        approvalEngine = ApprovalEngine()
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

        val agentTools: MutableList<com.mtzallqmy.aiagent.tools.AgentTool<*, *>> = mutableListOf()
        agentTools.addAll(FileToolSet(this, workspaceManager).tools)
        agentTools.addAll(HttpToolSet().tools)
        agentTools.addAll(ClipboardToolSet(this).tools)
        agentTools.addAll(DeviceToolSet(this).tools)
        agentTools.addAll(SshToolSet().tools)

        runtime = AgentRuntime(
            provider = providerRegistry.select("openai"),
            toolRuntime = toolRuntime,
        )
    }
}
