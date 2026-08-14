package com.mtzallqmy.aiagent.feature.chat

import com.mtzallqmy.aiagent.model.ApprovalRequest
import com.mtzallqmy.aiagent.model.ToolResultEnvelope

/** Product-facing chat stream. Private chain-of-thought is never represented. */
sealed interface ChatItem {
    val id: String
    val createdAt: Long

    data class UserMessage(
        override val id: String,
        val text: String,
        override val createdAt: Long,
        val attachments: List<ChatAttachment> = emptyList(),
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        override val createdAt: Long,
        val streaming: Boolean = false,
        val providerId: String? = null,
        val modelId: String? = null,
        val local: Boolean? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val cost: Double? = null,
    ) : ChatItem

    data class ToolCallCard(
        override val id: String,
        val toolName: String,
        val argumentsPreview: String = "",
        override val createdAt: Long,
    ) : ChatItem

    data class ToolResultCard(
        override val id: String,
        val result: ToolResultEnvelope,
        override val createdAt: Long,
    ) : ChatItem

    data class ApprovalCard(
        override val id: String,
        val request: ApprovalRequest,
        override val createdAt: Long,
    ) : ChatItem

    data class ArtifactCard(
        override val id: String,
        val name: String,
        val mimeType: String? = null,
        val uri: String? = null,
        override val createdAt: Long,
    ) : ChatItem

    data class ErrorCard(
        override val id: String,
        val message: String,
        val retryable: Boolean,
        override val createdAt: Long,
    ) : ChatItem

    data class SystemEvent(
        override val id: String,
        val label: String,
        val detail: String? = null,
        override val createdAt: Long,
    ) : ChatItem
}

data class ChatAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val uri: String? = null,
)

data class ChatUiState(
    val items: List<ChatItem> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val routingLabel: String? = null,
)

sealed interface ChatUiEvent {
    data class InputChanged(val value: String) : ChatUiEvent
    data class Send(val text: String) : ChatUiEvent
    data class Edit(val itemId: String, val text: String) : ChatUiEvent
    data class Regenerate(val itemId: String? = null) : ChatUiEvent
    data object Stop : ChatUiEvent
    data class ProviderSelected(val providerId: String) : ChatUiEvent
    data class ModelSelected(val modelId: String) : ChatUiEvent
}

object ChatRoute {
    const val route = "chat"
}
