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
import androidx.compose.material3.CircularProgressIndicator
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
import com.mtzallqmy.aiagent.local_llm.LocalModelLoadAssessment
import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.provider.local.LocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private data class ProviderLocalModelItem(
    val id: String,
    val name: String,
    val contextWindow: Int,
    val assessment: LocalModelLoadAssessment?,
    val loaded: Boolean = false,
    val tokensPerSecond: Double? = null,
)

private data class ProviderLocalModelsState(
    val loading: Boolean = true,
    val items: List<ProviderLocalModelItem> = emptyList(),
    val message: String? = null,
)

private class ProviderLocalModelsViewModel(
    private val provider: LocalProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(ProviderLocalModelsState())
    val state: StateFlow<ProviderLocalModelsState> = _state.asStateFlow()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true, message = null)
            provider.listModels()
                .onSuccess { models ->
                    val items = models.map { model ->
                        val assessment = runCatching { provider.assessModel(model.id) }.getOrNull()
                        ProviderLocalModelItem(
                            id = model.id,
                            name = model.name,
                            contextWindow = model.capabilities.contextWindow,
                            assessment = assessment,
                            loaded = _state.value.items.firstOrNull { it.id == model.id }?.loaded == true,
                        )
                    }
                    _state.value = ProviderLocalModelsState(loading = false, items = items)
                }
                .onFailure { _state.value = ProviderLocalModelsState(loading = false, message = it.message ?: "Local model discovery failed") }
        }
    }

    fun load(id: String) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        val assessment = item.assessment ?: return
        if (!assessment.canLoad) {
            _state.value = _state.value.copy(message = assessment.blockers.joinToString("\n"))
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching { provider.loadAssessedModel(id, assessment, acknowledgeWarnings = assessment.warnings.isNotEmpty()) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        items = _state.value.items.map { it.copy(loaded = it.id == id) },
                        message = "Model loaded",
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: "Model load failed") }
        }
    }

    fun unload() {
        scope.launch(Dispatchers.IO) {
            runCatching { provider.unload() }
                .onSuccess { _state.value = _state.value.copy(items = _state.value.items.map { it.copy(loaded = false) }, message = "Model unloaded") }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: "Model unload failed") }
        }
    }

    fun test(id: String) {
        val item = _state.value.items.firstOrNull { it.id == id && it.loaded } ?: return
        scope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            var completionTokens = 0
            runCatching {
                provider.generate(
                    GenerationRequest(
                        messages = listOf(ChatMessage(role = MessageRole.USER, content = "Reply with OK.")),
                        modelId = id,
                        maxTokens = 16,
                        temperature = 0.0,
                    ),
                ).collect { event ->
                    if (event is GenerationEvent.Usage) completionTokens += event.completionTokens
                    if (event is GenerationEvent.GenerationFailed) throw event.error
                }
            }.onSuccess {
                val seconds = (System.nanoTime() - started).coerceAtLeast(1) / 1_000_000_000.0
                _state.value = _state.value.copy(
                    items = _state.value.items.map { if (it.id == item.id) it.copy(tokensPerSecond = completionTokens / seconds) else it },
                    message = "Model test succeeded",
                )
            }.onFailure { _state.value = _state.value.copy(message = it.message ?: "Model test failed") }
        }
    }
}

@Composable
fun LocalProviderModelsScreen(provider: LocalProvider, modifier: Modifier = Modifier) {
    val viewModel = remember(provider) { ProviderLocalModelsViewModel(provider) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(provider) { viewModel.refresh() }

    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local Models", style = MaterialTheme.typography.headlineSmall)
        if (state.loading) CircularProgressIndicator()
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.items, key = { it.id }) { item ->
                val assessment = item.assessment
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text("Context: ${item.contextWindow}")
                        if (assessment != null) {
                            Text("Size: ${formatProviderBytes(assessment.fileSizeBytes)}")
                            Text("Quantization: ${assessment.metadata.quantizationType ?: "Unknown"}")
                            Text("Estimated RAM: ${formatProviderBytes(assessment.estimatedPeakMemoryBytes)}")
                            Text("SHA-256: ${assessment.sha256.take(12)}…")
                            assessment.blockers.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                            assessment.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                        }
                        item.tokensPerSecond?.let { Text("Performance: %.1f tok/s".format(it)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !item.loaded && assessment?.canLoad == true, onClick = { viewModel.load(item.id) }) { Text("Load") }
                            OutlinedButton(enabled = item.loaded, onClick = { viewModel.test(item.id) }) { Text("Test") }
                            OutlinedButton(enabled = item.loaded, onClick = { viewModel.unload() }) { Text("Unload") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatProviderBytes(value: Long): String = when {
    value >= 1L shl 30 -> "%.2f GiB".format(value.toDouble() / (1L shl 30))
    value >= 1L shl 20 -> "%.1f MiB".format(value.toDouble() / (1L shl 20))
    else -> "${value / 1024} KiB"
}
