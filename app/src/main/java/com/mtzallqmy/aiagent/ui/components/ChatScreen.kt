package com.mtzallqmy.aiagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.R
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.feature.chat.ChatViewModel
import com.mtzallqmy.aiagent.model.AgentState
import com.mtzallqmy.aiagent.model.MessageRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    runtime: AgentRuntime?,
    providers: ProviderRegistry?,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(runtime) { runtime?.let { ChatViewModel(it) } }
    val messages by (viewModel?.messages?.collectAsState() ?: mutableStateOf(emptyList()))
    val state by (viewModel?.state?.collectAsState() ?: mutableStateOf(AgentState.IDLE))

    var input by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf("openai") }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })

        LazyColumn(
            modifier = Modifier.weight(1f).padding(8.dp),
            state = rememberLazyListState(),
        ) {
            itemsIndexed(messages) { _, msg ->
                AgentMessageBubble(
                    text = msg.content,
                    isUser = msg.role == MessageRole.USER,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                Text(
                    text = selectedProvider,
                    modifier = Modifier.menuAnchor().weight(1f),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    providers?.all()?.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                selectedProvider = p.providerId
                                expanded = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    viewModel?.send(text, tools = emptyList())
                    input = ""
                }
            }) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.send))
            }
            if (state == AgentState.OBSERVING || state == AgentState.PLANNING ||
                state == AgentState.THINKING || state == AgentState.EXECUTING_TOOL
            ) {
                IconButton(onClick = { viewModel?.stop() }) {
                    Text(stringResource(R.string.stop))
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.ask_agent)) },
        )
    }
}

/** Wrapper of the core:ui bubble for ChatMessage. */
@Composable
private fun AgentMessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    MessageBubble(
        text = text.ifEmpty { "\u2026" },
        isUser = isUser,
        modifier = modifier,
    )
}
