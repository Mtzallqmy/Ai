package com.mtzallqmy.aiagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.R
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.feature.logs.LogEntry
import com.mtzallqmy.aiagent.feature.logs.RunLogs
import com.mtzallqmy.aiagent.feature.security.SecurityCenter
import com.mtzallqmy.aiagent.feature.security.SecurityReport
import com.mtzallqmy.aiagent.model.CapabilityAvailabilityState
import com.mtzallqmy.aiagent.security.CredentialScope
import com.mtzallqmy.aiagent.security.CredentialVault
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    capabilities: CapabilityRegistry?,
    modifier: Modifier = Modifier,
) {
    val statuses by produceState<List<Pair<String, String>>>(emptyList(), capabilities) {
        value = capabilities?.allStatuses()?.map { it.id.value to describe(it.state) } ?: emptyList()
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_tools)) })
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            items(statuses) { pair ->
                val (id, state) = pair
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(id)
                        Text(state)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    providerRegistry: ProviderRegistry?,
    settings: SecureSettings?,
    vault: CredentialVault?,
    modifier: Modifier = Modifier,
) {
    var results by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_providers)) })
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            items(providerRegistry?.all() ?: emptyList()) { provider ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    results[provider.providerId] ?: stringResource(R.string.not_tested),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val scope = rememberCoroutineScope()
                            val connectedText = stringResource(R.string.connected)
                            Button(
                                onClick = {
                                    scope.launch {
                                        val result = provider.testConnection()
                                        results = results + (provider.providerId to if (result.isSuccess) connectedText else result.exceptionOrNull()?.message ?: "error")
                                    }
                                },
                            ) { Text(stringResource(R.string.test)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCenterScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val report by produceState<SecurityReport?>(null, context) {
        value = kotlin.runCatching { SecurityCenter(context).collect() }.getOrNull()
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_security)) })
        if (report == null) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else {
            val r = report!!
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                item("accessibility" to r.accessibilityEnabled.toString()) {}
                item("notif" to r.notificationListenerEnabled.toString()) {}
            }
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Accessibility: ${r.accessibilityEnabled}")
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Notification listener: ${r.notificationListenerEnabled}")
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Notifications permission: ${r.notificationsPermission}")
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Contacts sandbox mode: ${r.sandboxContacts}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SecureSettings?,
    vault: CredentialVault?,
    modifier: Modifier = Modifier,
) {
    val arabic by (settings?.arabicLocale?.collectAsState(initial = false) ?: mutableStateOf(false))
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.arabic_mode))
                Switch(checked = arabic, onCheckedChange = {})
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        RunLogs.events.collect { entry ->
            entries = entries + entry
            if (entries.size > 100) entries = entries.drop(entries.size - 100)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_logs)) })
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            items(entries) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text("${entry.formattedTime} [${entry.level}] ", style = MaterialTheme.typography.bodySmall)
                    Text(entry.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun describe(state: CapabilityAvailabilityState): String = when (state) {
    CapabilityAvailabilityState.AVAILABLE -> "Available"
    CapabilityAvailabilityState.PERMISSION_REQUIRED -> "Permission required"
    CapabilityAvailabilityState.SERVICE_DISABLED -> "Service disabled"
    CapabilityAvailabilityState.BACKEND_UNAVAILABLE -> "Backend unavailable"
    CapabilityAvailabilityState.DEVICE_UNSUPPORTED -> "Device unsupported"
    CapabilityAvailabilityState.CONFIGURATION_REQUIRED -> "Configuration required"
    CapabilityAvailabilityState.SECURITY_DENIED -> "Security denied"
}
