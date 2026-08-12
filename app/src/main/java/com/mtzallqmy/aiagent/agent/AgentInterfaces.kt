package com.mtzallqmy.aiagent.agent

import kotlinx.coroutines.flow.Flow

/**
 * Normalized events from any AI Provider
 */
sealed class GenerationEvent {
    data object GenerationStarted : GenerationEvent()
    data class TextDelta(val text: String) : GenerationEvent()
    data class ReasoningSummaryDelta(val reasoning: String) : GenerationEvent()
    data class ToolCallStarted(val toolId: String) : GenerationEvent()
    data class ToolCallArgumentsDelta(val arguments: String) : GenerationEvent()
    data class ToolCallCompleted(val toolId: String, val result: String) : GenerationEvent()
    data class Usage(val promptTokens: Int, val completionTokens: Int) : GenerationEvent()
    data class GenerationCompleted(val finalResponse: String) : GenerationEvent()
    data class GenerationFailed(val error: Throwable) : GenerationEvent()
}

/**
 * Generic AI Provider interface
 */
interface AiProvider {
    val providerId: String
    suspend fun listModels(): Result<List<String>>
    suspend fun generate(prompt: String, model: String): Flow<GenerationEvent>
}

/**
 * Typed Tool interface as requested
 */
interface AgentTool<I : Any, O : Any> {
    val descriptor: ToolDescriptor
    suspend fun isAvailable(context: ToolContext): ToolAvailability
    suspend fun execute(input: I, context: ToolContext): ToolResult<O>
}

data class ToolDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val riskLevel: RiskLevel,
    val requiredCapabilities: Set<String>,
    val requiresApproval: Boolean
)

enum class RiskLevel { SAFE, READ, MODIFY, COMMUNICATION, FINANCIAL, SYSTEM_SENSITIVE }

data class ToolContext(val workspaceId: String, val agentId: String)

sealed class ToolAvailability {
    data object Available : ToolAvailability()
    data class Unavailable(val reason: String) : ToolAvailability()
}

sealed class ToolResult<out O : Any> {
    data class Success<O : Any>(val data: O, val metadata: Map<String, Any> = emptyMap()) : ToolResult<O>()
    data class Error(val message: String, val isRetryable: Boolean, val type: ErrorType) : ToolResult<Nothing>()
}

enum class ErrorType { GENERIC, PERMISSION, APPROVAL_DENIED, CAPABILITY_UNAVAILABLE }
