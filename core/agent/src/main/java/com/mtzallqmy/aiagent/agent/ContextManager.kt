package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.MessageRole

/**
 * Context manager: token budgeting, message truncation, duplicate suppression,
 * tool-output compression. Never sends full UI trees or terminal logs to the model.
 */
class ContextManager(
    private val contextWindow: Int = 4096,
    private val reserveForResponse: Int = 2048,
    private val maxToolOutputChars: Int = 3_000,
) {
    private val seenHashes = mutableSetOf<Int>()

    /**
     * Fits the message history into the remaining budget.
     * 1) Dedup adjacent identical tool messages.
     * 2) Truncate oldest non-system messages first.
     * 3) Compress long tool outputs.
     */
    fun fit(messages: List<ChatMessage>, estimatedTokens: Int): List<ChatMessage> {
        val budget = (contextWindow - reserveForResponse - estimatedTokens).coerceAtLeast(0)
        val deduped = messages.fold(mutableListOf<ChatMessage>()) { acc, msg ->
            val hash = msg.content.hashCode() + msg.role.hashCode()
            if (acc.isNotEmpty() && acc.last().role == MessageRole.TOOL && seenHashes.contains(hash) && msg.role == MessageRole.TOOL) acc
            else {
                if (msg.role == MessageRole.TOOL) seenHashes.add(hash)
                acc.add(msg); acc
            }
        }
        var remaining = deduped.toMutableList()
        var tokens = remaining.sumOf { estimateTokens(it.content) }
        while (tokens > budget && remaining.count { it.role != MessageRole.SYSTEM } > 1) {
            // remove oldest non-system message
            val idx = remaining.indexOfFirst { it.role != MessageRole.SYSTEM }
            if (idx < 0) break
            tokens -= estimateTokens(remaining[idx].content)
            remaining.removeAt(idx)
        }
        return remaining
    }

    fun compressToolOutput(output: String): String =
        if (output.length > maxToolOutputChars) {
            output.take(maxToolOutputChars) + "\n... [output truncated]"
        } else output

    /** Approximate tokens ≈ characters / 4 for CJK+Latin mix — real usage comes from provider usage events. */
    internal fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun reset() { seenHashes.clear() }
}
