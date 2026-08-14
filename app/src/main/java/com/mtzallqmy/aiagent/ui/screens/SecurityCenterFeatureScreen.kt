package com.mtzallqmy.aiagent.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.AegisApp
import com.mtzallqmy.aiagent.security.CredentialScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SecurityCenterState(
    val strongBoxAvailable: Boolean = false,
    val credentials: Map<String, Int> = emptyMap(),
    val rememberedApprovals: Int = 0,
    val pendingApprovals: Int = 0,
    val mcpToolCount: Int = 0,
    val localModelCount: Int? = null,
    val sandboxes: List<String> = emptyList(),
    val message: String? = null,
)

@Composable
fun SecurityCenterFeatureScreen(
    app: AegisApp,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf(SecurityCenterState()) }
    val scope = rememberCoroutineScope()

    suspend fun refresh(message: String? = null) {
        state = withContext(Dispatchers.IO) {
            val credentialCounts = listOf(
                CredentialScope.PROVIDER,
                CredentialScope.MCP,
                CredentialScope.SSH,
                CredentialScope.API_KEY_POOL,
            ).associate { it.id to app.vault.allNames(it).size }
            val sandboxStates = app.sandboxBackendRegistry.list().map { backend ->
                val health = runCatching { backend.healthCheck() }.getOrNull()
                buildString {
                    append(backend.id)
                    append(" · ")
                    append(backend.capabilities.isolationLevel.name)
                    append(" · ")
                    append(if (health?.available == true) "available" else "unavailable")
                    health?.message?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
            }
            val localModels = runCatching { app.localProvider.listModels().getOrThrow().size }.getOrNull()
            SecurityCenterState(
                strongBoxAvailable = app.vault.supportsStrongBox,
                credentials = credentialCounts,
                rememberedApprovals = app.approvalEngine.persistentRules().size,
                pendingApprovals = app.approvalEngine.pendingCount,
                mcpToolCount = app.toolRegistry.list().count { it.descriptor.id.startsWith("mcp.") },
                localModelCount = localModels,
                sandboxes = sandboxStates,
                message = message,
            )
        }
    }

    LaunchedEffect(app) { refresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Security Center", style = MaterialTheme.typography.headlineSmall) }
        state.message?.let { message -> item { Text(message) } }

        item {
            SecurityStatusCard(
                "Credential protection",
                if (state.strongBoxAvailable) "StrongBox available" else "Android Keystore / TEE",
            )
        }
        item { SecurityStatusCard("MCP tools currently registered", state.mcpToolCount.toString()) }
        item { SecurityStatusCard("Remembered approvals", state.rememberedApprovals.toString()) }
        item { SecurityStatusCard("Pending approvals", state.pendingApprovals.toString()) }
        item {
            SecurityStatusCard(
                "Local models discovered",
                state.localModelCount?.toString() ?: "Unavailable",
            )
        }

        item { Text("Credential scopes", style = MaterialTheme.typography.titleMedium) }
        items(state.credentials.entries.toList(), key = { it.key }) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${entry.key}: ${entry.value}")
                    OutlinedButton(
                        enabled = entry.value > 0,
                        onClick = {
                            val credentialScope = when (entry.key) {
                                CredentialScope.PROVIDER.id -> CredentialScope.PROVIDER
                                CredentialScope.MCP.id -> CredentialScope.MCP
                                CredentialScope.SSH.id -> CredentialScope.SSH
                                CredentialScope.API_KEY_POOL.id -> CredentialScope.API_KEY_POOL
                                else -> null
                            }
                            if (credentialScope != null) {
                                app.vault.clear(credentialScope)
                                scope.launch { refresh("Credential scope '${entry.key}' revoked") }
                            }
                        },
                    ) { Text("Revoke") }
                }
            }
        }

        item {
            Button(
                enabled = state.rememberedApprovals > 0,
                onClick = {
                    app.approvalEngine.persistentRules().forEach { app.approvalEngine.revoke(it.key) }
                    scope.launch { refresh("Remembered approval rules cleared") }
                },
            ) { Text("Reset remembered approvals") }
        }

        item { Text("Sandbox backends", style = MaterialTheme.typography.titleMedium) }
        if (state.sandboxes.isEmpty()) {
            item { Text("No sandbox backend registered") }
        } else {
            items(state.sandboxes) { value -> SecurityStatusCard("Sandbox", value) }
        }
    }
}

@Composable
private fun SecurityStatusCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value)
        }
    }
}
