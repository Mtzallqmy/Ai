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
 * - checks capability + availability first
 * - enforces timeouts and cancellation
 * - enforces per-run tool-call budgets
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
        maxToolCallsPerRun: Int = 50,
    ): ToolResultEnvelope {
        // 1. Budget
        val count = mutex.withLock {
            toolCallCounts.getOrDefault(runId, 0) + 1
        }
        if (count > maxToolCallsPerRun) {
            return ToolResultEnvelope(
                toolId = tool.descriptor.id, success = false, data = "",
                error = "Tool-call budget exceeded ($maxToolCallsPerRun)", durationMs = 0,
                isRetryable = false, errorCategory = ToolErrorCategory.GENERIC,
            )
        }
        mutex.withLock { toolCallCounts[runId] = count }

        val start = System.currentTimeMillis()

        // 2. Risk + approval (synchronous decision via ApprovalEngine)
        val approval = approvalEngine.decide(ApprovalRequest(
            toolName = tool.descriptor.displayName,
            action = "execute",
            target = tool.descriptor.id,
            argumentsSummary = input.toString().take(200),
            riskLevel = tool.descriptor.riskLevel,
            requestingAgent = "main",
            reason = "Requested during run $runId",
        ))
        if (approval.decision == ApprovalOption.DENY) {
            return ToolResultEnvelope(tool.descriptor.id, false, "", error = "Approval denied", durationMs = System.currentTimeMillis() - start, errorCategory = ToolErrorCategory.APPROVAL_REQUIRED)
        }

        // 3. Availability via Capability Registry
        val availability = tool.availability(context)
        if (availability !is ToolAvailability.Available) {
            return ToolResultEnvelope(tool.descriptor.id, false, "", error = (availability as ToolAvailability.Unavailable).reason, durationMs = System.currentTimeMillis() - start, errorCategory = ToolErrorCategory.CAPABILITY_UNAVAILABLE)
        }

        // 4. Capability gate
        for (cap in tool.descriptor.requiredCapabilities) {
            val capStatus = capabilityRegistry.status(cap)
            if (capStatus.state != CapabilityAvailabilityState.AVAILABLE) {
                return ToolResultEnvelope(tool.descriptor.id, false, "", error = "Capability ${cap.value} is ${capStatus.state}", durationMs = System.currentTimeMillis() - start, errorCategory = ToolErrorCategory.CAPABILITY_UNAVAILABLE)
            }
        }

        // 5. Timed execution with cancellation
        return try {
            withTimeout(tool.descriptor.timeoutMs) {
                val output = tool.execute(input, context)
                ToolResultEnvelope(tool.descriptor.id, true, output.toString(), durationMs = System.currentTimeMillis() - start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            ToolResultEnvelope(tool.descriptor.id, false, "", error = "Timeout", durationMs = tool.descriptor.timeoutMs, errorCategory = ToolErrorCategory.TIMEOUT)
        } catch (e: AgentException.ToolCancelledError) {
            ToolResultEnvelope(tool.descriptor.id, false, "", error = e.message, durationMs = System.currentTimeMillis() - start, errorCategory = ToolErrorCategory.CANCELLED)
        } catch (e: Throwable) {
            ToolResultEnvelope(tool.descriptor.id, false, "", error = e.message ?: "Unknown error", durationMs = System.currentTimeMillis() - start)
        }
    }
}

/** Approval decisions can be driven by policy config or a real approval channel. */
class ApprovalEngine(
    private val policyProvider: (RiskLevel) -> ApprovalPolicy = { ApprovalPolicy.ASK_EVERY_TIME },
) {
    private val perRiskAllowed = mutableSetOf<RiskLevel>()
    private val perRuleAlwaysAllowed = mutableSetOf<String>()

    fun allowRiskLevel(level: RiskLevel) { perRiskAllowed.add(level) }

    fun alwaysAllow(ruleKey: String) { perRuleAlwaysAllowed.add(ruleKey) }

    fun decide(request: ApprovalRequest): ApprovalDecision {
        val policy = policyProvider(request.riskLevel)
        val decision = when {
            perRuleAlwaysAllowed.contains(ruleKey(request)) -> ApprovalOption.ALWAYS_ALLOW
            perRiskAllowed.contains(request.riskLevel) -> ApprovalOption.ALLOW_ONCE
            policy == ApprovalPolicy.ALLOW -> ApprovalOption.ALLOW_ONCE
            policy == ApprovalPolicy.DENY -> ApprovalOption.DENY
            policy == ApprovalPolicy.ASK_ONCE -> ApprovalOption.ALLOW_ONCE
            else -> ApprovalOption.ALLOW_ONCE
        }
        return ApprovalDecision(request.id, decision)
    }

    private fun ruleKey(request: ApprovalRequest) = "${request.toolName}:${request.target}"
}
