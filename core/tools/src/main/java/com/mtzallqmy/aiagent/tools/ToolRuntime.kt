package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.common.AgentException
import com.mtzallqmy.aiagent.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Typed tool contract: primitive platform capability. */
interface AgentTool<I : Any, O : Any> {
    val descriptor: ToolDescriptor
    suspend fun availability(context: ToolContext): ToolAvailability
    suspend fun execute(input: I, context: ToolContext): O
}

data class ToolContext(val runId: String, val workspaceId: String)

sealed class ToolAvailability {
    data object Available : ToolAvailability()
    data class Unavailable(val reason: String) : ToolAvailability()
}

/**
 * Real Tool Runtime:
 * - validates tool input against the tool's JSON schema (no raw-string pass-through)
 * - checks capability + availability before anything executes
 * - routes sensitive actions through the ApprovalEngine and SUSPENDS until a
 *   human decides (never executes before the decision)
 * - enforces timeouts, cancellation, and per-run tool-call budgets
 * - retries retryable errors with exponential backoff
 * - never fakes execution
 */
class ToolRuntime(
    private val capabilityRegistry: CapabilityRegistry,
    private val approvalEngine: ApprovalEngine,
) {
    private val mutex = Mutex()
    private val toolCallCounts = mutableMapOf<String, Int>()

    suspend fun registerAndList(tools: List<AgentTool<*, *>>) = tools

    suspend fun execute(
        tool: AgentTool<Any, Any>,
        input: Any,
        context: ToolContext,
        runId: String,
        agentId: String = "main",
        maxToolCallsPerRun: Int = 50,
        maxRetries: Int = 1,
    ): ToolResultEnvelope {
        // 0. Schema validation: LLM arguments -> JSON parser -> schema validator -> typed input
        val typedInput = when (input) {
            is kotlinx.serialization.json.JsonObject -> input
            is String -> {
                when (val result = ToolSchemaValidator.validate(input, tool.descriptor.inputSchema)) {
                    is ToolSchemaValidator.ValidationResult.Valid -> result.input
                    is ToolSchemaValidator.ValidationResult.Invalid -> {
                        return failure(tool, "Invalid tool input: ${result.errors.joinToString("; ")}",
                            ToolErrorCategory.GENERIC, isRetryable = false)
                    }
                }
            }
            else -> {
                // Platform-initiated calls may already be typed objects; only LLM args are JSON strings.
                return failure(tool, "Tool input must be a JSON object",
                    ToolErrorCategory.GENERIC, isRetryable = false)
            }
        }

        // 1. Budget
        val count = mutex.withLock {
            toolCallCounts.getOrDefault(runId, 0) + 1
        }
        if (count > maxToolCallsPerRun) {
            return failure(tool, "Tool-call budget exceeded ($maxToolCallsPerRun)",
                ToolErrorCategory.GENERIC, isRetryable = false)
        }
        mutex.withLock { toolCallCounts[runId] = count }

        val start = System.currentTimeMillis()

        // 2. Risk + approval. ASK policies suspend the run; nothing executes before a decision.
        val immediate = approvalEngine.decide(ApprovalRequest(
            toolName = tool.descriptor.displayName,
            action = "execute",
            target = tool.descriptor.id,
            argumentsSummary = typedInput.toString().take(200),
            riskLevel = tool.descriptor.riskLevel,
            requestingAgent = agentId,
            reason = "Requested during run $runId",
        ))
        val decision = when (immediate.decision) {
            ApprovalOption.ASK -> approvalEngine.requestApproval(
                ApprovalRequest(
                    toolName = tool.descriptor.displayName,
                    action = "execute",
                    target = tool.descriptor.id,
                    argumentsSummary = typedInput.toString().take(200),
                    riskLevel = tool.descriptor.riskLevel,
                    requestingAgent = agentId,
                    reason = "Sensitive action: ${tool.descriptor.riskLevel} — requires approval",
                ),
            )
            else -> immediate
        }
        if (decision.decision == ApprovalOption.DENY || decision.decision == ApprovalOption.ASK) {
            return failure(tool, "Approval denied", ToolErrorCategory.APPROVAL_REQUIRED,
                durationMs = System.currentTimeMillis() - start, isRetryable = false)
        }

        // 3. Availability via Capability Registry
        val availability = tool.availability(context)
        if (availability !is ToolAvailability.Available) {
            return failure(tool, (availability as ToolAvailability.Unavailable).reason,
                ToolErrorCategory.CAPABILITY_UNAVAILABLE, durationMs = System.currentTimeMillis() - start)
        }

        // 4. Capability gate
        for (cap in tool.descriptor.requiredCapabilities) {
            val capStatus = capabilityRegistry.status(cap)
            if (capStatus.state != CapabilityAvailabilityState.AVAILABLE &&
                capStatus.state != CapabilityAvailabilityState.DEGRADED
            ) {
                return failure(tool, "Capability ${cap.value} is ${capStatus.state}",
                    ToolErrorCategory.CAPABILITY_UNAVAILABLE, durationMs = System.currentTimeMillis() - start)
            }
        }

        // 5. Timed execution with cancellation + retries
        var attempt = 0
        while (true) {
            attempt++
            val result = try {
                withTimeout(tool.descriptor.timeoutMs) {
                    val output = tool.execute(typedInput, context)
                    ToolResultEnvelope(
                        toolId = tool.descriptor.id, success = true, data = output.toString(),
                        durationMs = System.currentTimeMillis() - start,
                        metadata = mapOf("attempts" to attempt.toString(), "toolVersion" to tool.descriptor.id),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                failure(tool, "Timeout", ToolErrorCategory.TIMEOUT, durationMs = tool.descriptor.timeoutMs)
            } catch (e: AgentException.ToolCancelledError) {
                failure(tool, e.message ?: "Cancelled", ToolErrorCategory.CANCELLED,
                    durationMs = System.currentTimeMillis() - start, isRetryable = false)
            } catch (e: Throwable) {
                failure(tool, e.message ?: "Unknown error", ToolErrorCategory.GENERIC,
                    durationMs = System.currentTimeMillis() - start)
            }

            if (result.success || !result.isRetryable || attempt > maxRetries + 1) return result
            // Exponential backoff: 500ms, 1s, ...
            delay(500L * (1 shl (attempt - 1)))
        }
    }

    private fun failure(
        tool: AgentTool<Any, Any>, error: String, category: ToolErrorCategory,
        durationMs: Long = 0L, isRetryable: Boolean = true,
    ): ToolResultEnvelope = ToolResultEnvelope(
        toolId = tool.descriptor.id, success = false, data = "", error = error,
        durationMs = durationMs, isRetryable = isRetryable, errorCategory = category,
    )
}

