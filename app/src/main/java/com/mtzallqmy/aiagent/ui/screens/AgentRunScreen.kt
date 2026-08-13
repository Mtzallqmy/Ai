package com.mtzallqmy.aiagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.model.AgentRun
import com.mtzallqmy.aiagent.model.AgentState
import com.mtzallqmy.aiagent.model.RunTimelineEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object AgentRunRoute {
    const val route = "agent-run/{runId}"
    fun create(runId: String) = "agent-run/$runId"
}

enum class AgentRunPhase(val label: String) {
    PLANNING("Planning"), ROUTING("Routing"), PROVIDER_CALL("Provider call"), TOOL_REQUESTED("Tool requested"),
    WAITING_FOR_APPROVAL("Waiting for approval"), APPROVED("Approved"), EXECUTING("Executing"), OBSERVING("Observing"),
    VERIFYING("Verifying"), REPLANNING("Replanning"), COMPLETED("Completed"), FAILED("Failed"), CANCELLED("Cancelled"),
}

data class AgentRunTimelineItem(
    val phase: AgentRunPhase,
    val label: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMillis: Long?,
    val error: String?,
)

data class AgentRunUiState(
    val runId: String? = null,
    val status: AgentState = AgentState.IDLE,
    val provider: String? = null,
    val model: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val toolCalls: Int = 0,
    val approvals: Int = 0,
    val errors: Int = 0,
    val cost: Double = 0.0,
    val durationMillis: Long? = null,
    val timeline: List<AgentRunTimelineItem> = emptyList(),
)

sealed interface AgentRunUiEvent { data object Pause : AgentRunUiEvent; data object Resume : AgentRunUiEvent; data object Cancel : AgentRunUiEvent }

/** Observability only. It deliberately never receives or exposes model private reasoning. */
class AgentRunViewModel(
    private val runtime: AgentRuntime,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(AgentRunUiState())
    val state: StateFlow<AgentRunUiState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(runtime.run, runtime.state, runtime.timeline) { run, status, timeline -> buildState(run, status, timeline) }
                .collect { _state.value = it }
        }
    }

    fun onEvent(event: AgentRunUiEvent) {
        when (event) {
            AgentRunUiEvent.Pause -> runtime.pause()
            AgentRunUiEvent.Resume -> runtime.resume()
            AgentRunUiEvent.Cancel -> runtime.cancel()
        }
    }

    private fun buildState(run: AgentRun?, status: AgentState, timeline: List<RunTimelineEntry>) = AgentRunUiState(
        runId = run?.runId,
        status = status,
        provider = run?.provider,
        model = run?.model,
        promptTokens = run?.promptTokens ?: 0,
        completionTokens = run?.completionTokens ?: 0,
        toolCalls = run?.toolCalls ?: 0,
        approvals = run?.approvals ?: 0,
        errors = run?.errors ?: 0,
        cost = run?.estimatedCost ?: 0.0,
        durationMillis = run?.let { (it.completedAt ?: System.currentTimeMillis()) - it.startedAt },
        timeline = timeline.map(::toTimelineItem),
    )

    private fun toTimelineItem(entry: RunTimelineEntry): AgentRunTimelineItem {
        val label = entry.label.lowercase()
        val phase = when {
            "approval" in label && "waiting" in label -> AgentRunPhase.WAITING_FOR_APPROVAL
            "approved" in label -> AgentRunPhase.APPROVED
            "routing" in label -> AgentRunPhase.ROUTING
            "provider" in label -> AgentRunPhase.PROVIDER_CALL
            "tool" in label && ("request" in label || "waiting" in label) -> AgentRunPhase.TOOL_REQUESTED
            "execut" in label -> AgentRunPhase.EXECUTING
            "observ" in label || "result" in label -> AgentRunPhase.OBSERVING
            "verify" in label -> AgentRunPhase.VERIFYING
            "continued" in label || "replan" in label || "retry" in label -> AgentRunPhase.REPLANNING
            "completed" in label -> AgentRunPhase.COMPLETED
            "cancel" in label -> AgentRunPhase.CANCELLED
            "failed" in label || "error" in label || "timeout" in label -> AgentRunPhase.FAILED
            else -> AgentRunPhase.PLANNING
        }
        return AgentRunTimelineItem(phase, entry.label, entry.startedAt, entry.endedAt, entry.endedAt?.minus(entry.startedAt), entry.error)
    }
}

@Composable
fun AgentRunScreen(runtime: AgentRuntime, modifier: Modifier = Modifier) {
    val viewModel = remember(runtime) { AgentRunViewModel(runtime) }
    val state by viewModel.state.collectAsState()
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Agent Run", style = MaterialTheme.typography.headlineSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Status: ${state.status.name}")
            state.durationMillis?.let { Text("${it} ms") }
        }
        Text("Provider: ${state.provider ?: "—"} / Model: ${state.model ?: "—"}")
        Text("Tokens: ${state.promptTokens} + ${state.completionTokens} · Tools: ${state.toolCalls} · Approvals: ${state.approvals}")
        Text("Errors: ${state.errors} · Cost: ${"%.4f".format(state.cost)}")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.timeline) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.phase.label, style = MaterialTheme.typography.titleSmall)
                        Text(item.label)
                        Text("Start: ${item.startedAt}")
                        item.durationMillis?.let { Text("Duration: $it ms") }
                        item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
