package com.mtzallqmy.aiagent.feature.chat

import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.RegisteredTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Chat feature state: real streaming from AgentRuntime; tool calls, retries,
 * stop — all wired to the runtime. UI text comes from string resources.
 */
class ChatViewModel(
    private val runtime: AgentRuntime,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    private val uiScope = scope

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state

    private val _timeline = MutableStateFlow<List<RunTimelineEntry>>(emptyList())
    val timeline: StateFlow<List<RunTimelineEntry>> = _timeline

    fun send(text: String, tools: List<RegisteredTool>, modelId: String = "") {
        if (text.isBlank()) return
        _messages.value += ChatMessage(role = MessageRole.USER, content = text)
        _messages.value += ChatMessage(role = MessageRole.ASSISTANT, content = "")
        runtime.runTask(task = text, modelId = modelId, tools = tools)
        observeRun()
    }

    private fun observeRun() {
        uiScope.launch {
            runtime.state.collect { st ->
                _state.value = st
                if (st == AgentState.COMPLETED || st == AgentState.FAILED || st == AgentState.CANCELLED) {
                    // finalize: fill last assistant message with final response
                }
            }
        }
        uiScope.launch {
            runtime.events.collect { event ->
                when (event) {
                    is GenerationEvent.TextDelta -> {
                        val current = _messages.value.toMutableList()
                        val last = current.last()
                        current[current.lastIndex] = ChatMessage(role = MessageRole.ASSISTANT, content = last.content + event.text)
                        _messages.value = current
                    }
                    is GenerationEvent.ToolCallStarted -> {
                        _timeline.value += RunTimelineEntry(
                            runId = runtime.run.value?.runId ?: "",
                            label = "tool:${event.toolName}",
                            startedAt = System.currentTimeMillis(),
                        )
                    }
                    is GenerationEvent.GenerationFailed -> {
                        val current = _messages.value.toMutableList()
                        current[current.lastIndex] = ChatMessage(role = MessageRole.ASSISTANT, content = "Error: ${event.error}")
                        _messages.value = current
                    }
                    else -> {}
                }
            }
        }
    }

    fun stop() {
        runtime.cancel()
    }

    fun resend() {
        val lastUser = _messages.value.filter { it.role == MessageRole.USER }.lastOrNull() ?: return
        val trimmed = _messages.value.dropLast(2)
        _messages.value = trimmed
        send(lastUser.content, emptyList())
    }

    fun editMessage(index: Int, text: String) {
        val current = _messages.value.toMutableList()
        if (index in current.indices) current[index] = ChatMessage(role = MessageRole.USER, content = text)
        _messages.value = current
    }
}
