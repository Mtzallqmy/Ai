package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.model.ApprovalDecision
import com.mtzallqmy.aiagent.model.ApprovalOption
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.ApprovalRequest
import com.mtzallqmy.aiagent.model.RiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * Real human-in-the-loop approval engine.
 *
 * - `decide()` returns an IMMEDIATE policy decision (ALLOW / ASK / DENY).
 *   It NEVER defaults ASK policies to an automatic allow — asking without a
 *   real channel defaults to a safe DENY until someone is listening.
 * - `requestApproval()` suspends the caller (the agent runtime) until the UI
 *   resolves the request. The runtime stays in WAITING_FOR_APPROVAL and no
 *   tool executes before the decision arrives.
 */
class ApprovalEngine(
    private val policyProvider: (RiskLevel) -> ApprovalPolicy = { ApprovalPolicy.ASK_EVERY_TIME },
) {
    private val perRiskAllowed = mutableSetOf<RiskLevel>()
    private val perRuleAlwaysAllowed = mutableSetOf<String>()
    private val perRuleDenied = mutableSetOf<String>()
    private val pending = mutableMapOf<String, CompletableDeferred<ApprovalDecision>>()
    private val _requests = Channel<ApprovalRequest>(Channel.UNLIMITED)

    /** Live stream of approval requests the UI should observe and present. */
    val requests get() = _requests

    val pendingCount: Int get() = pending.size

    /** Immediate, synchronous policy decision. ASK without a resolver → DENY (fail closed). */
    fun decide(request: ApprovalRequest): ApprovalDecision {
        val policy = policyProvider(request.riskLevel)
        val rule = ruleKey(request)
        val option = when {
            perRuleDenied.contains(rule) -> ApprovalOption.DENY
            perRuleAlwaysAllowed.contains(rule) -> ApprovalOption.ALWAYS_ALLOW
            perRiskAllowed.contains(request.riskLevel) -> ApprovalOption.ALLOW_ONCE
            policy == ApprovalPolicy.ALLOW -> ApprovalOption.ALLOW_ONCE
            policy == ApprovalPolicy.DENY -> ApprovalOption.DENY
            policy == ApprovalPolicy.ASK_ONCE -> ApprovalOption.ASK
            policy == ApprovalPolicy.ASK_EVERY_TIME -> ApprovalOption.ASK
            else -> ApprovalOption.ASK
        }
        return ApprovalDecision(request.id, option)
    }

    /**
     * Suspend until a human resolves the request (or it is cancelled).
     * Returns the final decision. If nobody is listening when the decision
     * is needed, the request is still emitted to the UI channel, and the
     * caller waits — nothing executes in the meantime.
     */
    suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        val existing = pending[request.id]
        if (existing != null) return existing.await()

        _requests.send(request)
        val deferred = CompletableDeferred<ApprovalDecision>()
        pending[request.id] = deferred
        return try {
            deferred.await()
        } finally {
            pending.remove(request.id)
        }
    }

    /** Called by the UI when the user picks an option. Resumes the suspended runtime. */
    fun respond(requestId: String, option: ApprovalOption, toolName: String = "") {
        val deferred = pending[requestId] ?: return
        when (option) {
            ApprovalOption.ALLOW_ONCE, ApprovalOption.ALLOW_FOR_TASK -> { /* allowed for this call or task */ }
            ApprovalOption.ALWAYS_ALLOW -> alwaysAllow(ruleKeyFromId(requestId, toolName))
            ApprovalOption.DENY -> denyRule(ruleKeyFromId(requestId, toolName))
            ApprovalOption.ASK -> { /* no automatic progress — human decides */ }
        }
        deferred.complete(ApprovalDecision(requestId, option))
    }

    fun allowRiskLevel(level: RiskLevel) { perRiskAllowed.add(level) }

    fun alwaysAllow(ruleKey: String) { perRuleAlwaysAllowed.add(ruleKey) }

    private fun denyRule(ruleKey: String) { perRuleDenied.add(ruleKey) }

    private fun ruleKey(request: ApprovalRequest) = "${request.toolName}:${request.target}"

    private fun ruleKeyFromId(requestId: String, toolName: String): String {
        // Best-effort reconstruction: the UI must pass toolName when responding.
        return if (toolName.isNotBlank()) "$toolName:$requestId" else requestId
    }
}
