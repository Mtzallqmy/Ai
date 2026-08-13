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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.local_llm.DiscoveredLocalModel
import com.mtzallqmy.aiagent.local_llm.LocalGenerationEvent
import com.mtzallqmy.aiagent.local_llm.LocalGenerationOptions
import com.mtzallqmy.aiagent.local_llm.LocalModelBackend
import com.mtzallqmy.aiagent.local_llm.LocalModelLoadAssessment
import com.mtzallqmy.aiagent.local_llm.LocalModelLoadOptions
import com.mtzallqmy.aiagent.local_llm.LocalModelReference
import com.mtzallqmy.aiagent.local_llm.LocalModelState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

object LocalModelsRoute { const val route = "local-models" }

data class LocalModelUiItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val quantization: String,
    val contextSize: Long?,
    val estimatedRamBytes: Long?,
    val checksumStatus: String,
    val loadStatus: String,
    val loadInProgress: Boolean,
    val tokensPerSecond: Double?,
    val blockers: List<String>,
    val warnings: List<String>,
    internal val reference: LocalModelReference,
    internal val assessmentId: String?,
)

data class LocalModelsUiState(
    val loading: Boolean = true,
    val models: List<LocalModelUiItem> = emptyList(),
    val message: String? = null,
)

sealed interface LocalModelsUiEvent {
    data object Refresh : LocalModelsUiEvent
    data class Load(val modelId: String) : LocalModelsUiEvent
    data object Unload : LocalModelsUiEvent
    data class TestModel(val modelId: String) : LocalModelsUiEvent
    data class Delete(val modelId: String) : LocalModelsUiEvent
}

class LocalModelsViewModel(
    private val backend: LocalModelBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(LocalModelsUiState())
    val state: StateFlow<LocalModelsUiState> = _state.asStateFlow()
    private val assessments = mutableMapOf<String, LocalModelLoadAssessment>()
    private var discovered = emptyMap<String, DiscoveredLocalModel>()

    init {
        scope.launch { backend.state.collect { applyBackendState(it) } }
        refresh()
    }

    fun onEvent(event: LocalModelsUiEvent) {
        when (event) {
            LocalModelsUiEvent.Refresh -> refresh()
            is LocalModelsUiEvent.Load -> load(event.modelId)
            LocalModelsUiEvent.Unload -> scope.launch { runCatching { backend.unload() }.onFailure(::showError); refresh() }
            is LocalModelsUiEvent.TestModel -> test(event.modelId)
            is LocalModelsUiEvent.Delete -> delete(event.modelId)
        }
    }

    private fun refresh() {
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching { backend.discoverModels() }
                .onSuccess { models ->
                    discovered = models.associateBy(::stableId)
                    val items = models.map { model ->
                        val id = stableId(model)
                        val assessment = runCatching { backend.assessLoad(model.reference, LocalModelLoadOptions()) }.getOrNull()
                        if (assessment != null) assessments[id] = assessment
                        model.toUi(id, assessment)
                    }
                    _state.value = LocalModelsUiState(loading = false, models = items)
                }
                .onFailure { _state.value = LocalModelsUiState(loading = false, message = it.message ?: "Unable to discover local models") }
        }
    }

    private fun load(modelId: String) {
        val model = discovered[modelId] ?: return
        val assessment = assessments[modelId] ?: return
        if (!assessment.canLoad) {
            _state.value = _state.value.copy(message = assessment.blockers.joinToString("\n"))
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                backend.load(model.reference, LocalModelLoadOptions(), assessment.assessmentId, acknowledgeWarnings = assessment.warnings.isNotEmpty())
            }.onFailure(::showError)
        }
    }

    private fun test(modelId: String) {
        if (modelId !in discovered) return
        scope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            var generatedTokens = 0
            runCatching {
                backend.generate("Reply with the single word OK.", LocalGenerationOptions(temperature = 0f, maxTokens = 16)).collect { event ->
                    if (event is LocalGenerationEvent.Completed) generatedTokens = event.usage.generatedTokens
                }
            }.onSuccess {
                val seconds = (System.nanoTime() - started).coerceAtLeast(1L) / 1_000_000_000.0
                _state.value = _state.value.copy(
                    models = _state.value.models.map { item -> if (item.id == modelId) item.copy(tokensPerSecond = generatedTokens / seconds) else item },
                    message = "Model test succeeded",
                )
            }.onFailure(::showError)
        }
    }

    private fun delete(modelId: String) {
        val model = discovered[modelId] ?: return
        scope.launch(Dispatchers.IO) {
            val loadedPath = (backend.state.value as? LocalModelState.Ready)?.model?.reference?.canonicalPath
            if (loadedPath == model.reference.canonicalPath) {
                _state.value = _state.value.copy(message = "Unload the model before deleting it")
                return@launch
            }
            runCatching {
                val file = File(model.reference.canonicalPath).canonicalFile
                check(file.isFile && file.extension.equals("gguf", ignoreCase = true))
                check(file.delete()) { "Model could not be deleted" }
            }.onSuccess { refresh() }.onFailure(::showError)
        }
    }

    private fun applyBackendState(nativeState: LocalModelState) {
        val loadedPath = when (nativeState) {
            is LocalModelState.Ready -> nativeState.model.reference.canonicalPath
            is LocalModelState.Generating -> nativeState.model.reference.canonicalPath
            is LocalModelState.Embedding -> nativeState.model.reference.canonicalPath
            else -> null
        }
        _state.value = _state.value.copy(
            models = _state.value.models.map { item ->
                val path = discovered[item.id]?.reference?.canonicalPath
                item.copy(
                    loadStatus = when {
                        loadedPath == path -> "Loaded"
                        nativeState is LocalModelState.Loading && nativeState.path == path -> "Loading"
                        else -> "Unloaded"
                    },
                    loadInProgress = nativeState is LocalModelState.Loading && nativeState.path == path,
                )
            },
            message = (nativeState as? LocalModelState.Failed)?.message ?: _state.value.message,
        )
    }

    private fun DiscoveredLocalModel.toUi(id: String, assessment: LocalModelLoadAssessment?) = LocalModelUiItem(
        id = id,
        name = metadata.name?.takeIf { it.isNotBlank() } ?: "Local GGUF model",
        sizeBytes = fileSizeBytes,
        quantization = metadata.quantizationType?.let { "GGUF type $it" } ?: "Unknown",
        contextSize = metadata.trainedContextSize,
        estimatedRamBytes = assessment?.estimatedPeakMemoryBytes,
        checksumStatus = when {
            assessment == null -> "Not checked"
            assessment.blockers.any { it.contains("checksum", ignoreCase = true) } -> "Mismatch"
            else -> "Verified"
        },
        loadStatus = "Unloaded",
        loadInProgress = false,
        tokensPerSecond = null,
        blockers = assessment?.blockers.orEmpty(),
        warnings = assessment?.warnings.orEmpty(),
        reference = reference,
        assessmentId = assessment?.assessmentId,
    )

    private fun stableId(model: DiscoveredLocalModel): String = UUID.nameUUIDFromBytes(model.reference.canonicalPath.toByteArray()).toString()
    private fun showError(error: Throwable) { _state.value = _state.value.copy(message = error.message ?: "Local model operation failed") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(backend: LocalModelBackend, modifier: Modifier = Modifier) {
    val viewModel = remember(backend) { LocalModelsViewModel(backend) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.onEvent(LocalModelsUiEvent.Refresh) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Local Models") })
        if (state.loading) CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        state.message?.let { Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(state.models, key = { it.id }) { model ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        Text("Size: ${formatBytes(model.sizeBytes)}")
                        Text("Quantization: ${model.quantization}")
                        Text("Context: ${model.contextSize ?: "Unknown"}")
                        Text("Estimated RAM: ${model.estimatedRamBytes?.let(::formatBytes) ?: "Unknown"}")
                        Text("Checksum: ${model.checksumStatus}")
                        Text("Status: ${model.loadStatus}")
                        model.tokensPerSecond?.let { Text("Performance: %.1f tok/s".format(it)) }
                        if (model.loadInProgress) CircularProgressIndicator()
                        model.blockers.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        model.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = model.blockers.isEmpty() && model.loadStatus == "Unloaded", onClick = { viewModel.onEvent(LocalModelsUiEvent.Load(model.id)) }) { Text("Load") }
                            OutlinedButton(enabled = model.loadStatus == "Loaded", onClick = { viewModel.onEvent(LocalModelsUiEvent.TestModel(model.id)) }) { Text("Test model") }
                            OutlinedButton(enabled = model.loadStatus != "Loaded" && !model.loadInProgress, onClick = { viewModel.onEvent(LocalModelsUiEvent.Delete(model.id)) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1L shl 30 -> "%.2f GiB".format(value.toDouble() / (1L shl 30))
    value >= 1L shl 20 -> "%.1f MiB".format(value.toDouble() / (1L shl 20))
    else -> "${value / 1024} KiB"
}
