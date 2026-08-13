package com.mtzallqmy.aiagent.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mtzallqmy.aiagent.AegisApp
import com.mtzallqmy.aiagent.local_llm.LocalModelState
import com.mtzallqmy.aiagent.security.CredentialScope
import com.mtzallqmy.aiagent.sandbox.SandboxBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SecurityCenterRoute {
    const val route = "security-center"
}

data class SecurityCredentialStatus(
    val scope: String,
    val count: Int,
)

data class SecuritySandboxStatus(
    val id: String,
    val available: Boolean,
    val isolation: String,
    val message: String,
)

data class SecurityCenterUiState(
    val accessibilityEnabled: Boolean = false,
    val dangerousPermissions: List<String> = emptyList(),
    val activeMcpServers: List<String> = emptyList(),
    val credentialStatus: List<SecurityCredentialStatus> = emptyList(),
    val localModelPrivacy: String = "Unloaded",
    val sandboxes: List<SecuritySandboxStatus> = emptyList(),
    val rememberedApprovalRules: Int = 0,
    val sshCredentialCount: Int = 0,
    val secretScanStatus: String = "CI status unavailable in-app",
    val message: String? = null,
)

sealed interface SecurityCenterUiEvent {
    data object Refresh : SecurityCenterUiEvent
    data class RevokeCredentialScope(val scopeId: String) : SecurityCenterUiEvent
    data object ResetApprovalRules : SecurityCenterUiEvent
    data object DisconnectAllMcp : SecurityCenterUiEvent
}

/**
 * Aggregates only security metadata. Credential plaintext, OAuth tokens, SSH keys,
 * prompt content and private model reasoning are never read into this state.
 */
class SecurityCenterViewModel(
    private val context: Context,
    private val app: AegisApp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(SecurityCenterUiState())
    val state: StateFlow<SecurityCenterUiState> = _state.asStateFlow()

    fun onEvent(event: SecurityCenterUiEvent) {
        when (event) {
            SecurityCenterUiEvent.Refresh -> refresh()
            is SecurityCenterUiEvent.RevokeCredentialScope -> revoke(event.scopeId)
            SecurityCenterUiEvent.ResetApprovalRules -> {
                app.approvalEngine.persistentRules().forEach { app.approvalEngine.revoke(it.key) }
                refresh("Remembered approval rules cleared")
            }
            SecurityCenterUiEvent.DisconnectAllMcp -> scope.launch {
                app.mcpRuntime.connections().map { it.serverId }.forEach { app.mcpRuntime.disconnect(it) }
                refresh("MCP sessions disconnected")
            }
        }
    }

    private fun refresh(message: String? = null) {
        scope.launch(Dispatchers.IO) {
            val mcp = runCatching { app.mcpRuntime.connections() }.getOrDefault(emptyList())
            val sandboxStates = app.sandboxBackendRegistry.list().map { backend -> backend.toSecurityStatus() }
            val credentialStatus = listOf(
                CredentialScope.PROVIDER,
                CredentialScope.MCP,
                CredentialScope.SSH,
                CredentialScope.API_KEY_POOL,
            ).map { credentialScope ->
                SecurityCredentialStatus(
                    scope = credentialScope.id,
                    count = app.vault.allNames(credentialScope).size,
                )
            }
            _state.value = SecurityCenterUiState(
                accessibilityEnabled = accessibilityEnabled(context),
                dangerousPermissions = grantedDangerousPermissions(context),
                activeMcpServers = mcp.map { connection ->
                    "${connection.serverId}: ${if (connection.healthy) "healthy" else "unhealthy"}"
                },
                credentialStatus = credentialStatus,
                localModelPrivacy = when (app.localModelBackend.state.value) {
                    is LocalModelState.Idle -> "Local model unloaded"
                    is LocalModelState.Loading -> "Loading on device"
                    is LocalModelState.Ready -> "Loaded on device"
                    is LocalModelState.Generating -> "Generating on device"
                    is LocalModelState.Embedding -> "Embedding on device"
                    is LocalModelState.Failed -> "Local model error"
                },
                sandboxes = sandboxStates,
                rememberedApprovalRules = app.approvalEngine.persistentRules().size,
                sshCredentialCount = app.vault.allNames(CredentialScope.SSH).size,
                message = message,
            )
        }
    }

    private fun revoke(scopeId: String) {
        val credentialScope = when (scopeId) {
            CredentialScope.PROVIDER.id -> CredentialScope.PROVIDER
            CredentialScope.MCP.id -> CredentialScope.MCP
            CredentialScope.SSH.id -> CredentialScope.SSH
            CredentialScope.API_KEY_POOL.id -> CredentialScope.API_KEY_POOL
            else -> return
        }
        app.vault.clear(credentialScope)
        refresh("Credential scope '$scopeId' cleared")
    }

    private suspend fun SandboxBackend.toSecurityStatus(): SecuritySandboxStatus {
        val health = runCatching { healthCheck() }.getOrElse {
            return SecuritySandboxStatus(id, false, capabilities.isolationLevel.name, it.message ?: "Health check failed")
        }
        return SecuritySandboxStatus(id, health.available, capabilities.isolationLevel.name, health.message)
    }

    private fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    private fun grantedDangerousPermissions(context: Context): List<String> = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ).filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }.map { it.substringAfterLast('.') }
}

@Composable
fun SecurityCenterFeatureScreen(
    app: AegisApp,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel = remember(app) { SecurityCenterViewModel(context.applicationContext, app) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.onEvent(SecurityCenterUiEvent.Refresh) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Security Center", style = MaterialTheme.typography.headlineSmall) }
        state.message?.let { message -> item { Text(message) } }
        item { SecurityRow("Accessibility", if (state.accessibilityEnabled) "Enabled" else "Disabled") }
        item { SecurityRow("Dangerous permissions", state.dangerousPermissions.joinToString().ifBlank { "None" }) }
        item { SecurityRow("Local model privacy", state.localModelPrivacy) }
        item { SecurityRow("SSH credentials", state.sshCredentialCount.toString()) }
        item { SecurityRow("Secret scan", state.secretScanStatus) }

        item { Text("Active MCP servers", style = MaterialTheme.typography.titleMedium) }
        if (state.activeMcpServers.isEmpty()) item { Text("None") }
        else items(state.activeMcpServers) { Text(it) }
        item {
            OutlinedButton(
                enabled = state.activeMcpServers.isNotEmpty(),
                onClick = { viewModel.onEvent(SecurityCenterUiEvent.DisconnectAllMcp) },
            ) { Text("Disconnect all MCP") }
        }

        item { Text("Credentials", style = MaterialTheme.typography.titleMedium) }
        items(state.credentialStatus) { credential ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${credential.scope}: ${credential.count}")
                    OutlinedButton(
                        enabled = credential.count > 0,
                        onClick = { viewModel.onEvent(SecurityCenterUiEvent.RevokeCredentialScope(credential.scope)) },
                    ) { Text("Revoke") }
                }
            }
        }

        item { Text("Sandbox backends", style = MaterialTheme.typography.titleMedium) }
        items(state.sandboxes) { sandbox ->
            SecurityRow(
                sandbox.id,
                "${if (sandbox.available) "Available" else "Unavailable"} · ${sandbox.isolation} · ${sandbox.message}",
            )
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Remembered approvals: ${state.rememberedApprovalRules}")
                Button(
                    enabled = state.rememberedApprovalRules > 0,
                    onClick = { viewModel.onEvent(SecurityCenterUiEvent.ResetApprovalRules) },
                ) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun SecurityRow(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value)
        }
    }
}
