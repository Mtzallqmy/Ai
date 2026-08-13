package com.mtzallqmy.aiagent.common

/**
 * Sanitizes sensitive values (API keys, secrets) before they can reach
 * logs, crash reports, analytics, memory, or tool results.
 */
object SecretSanitizer {
    private val SECRET_PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9\\-_]{20,}", RegexOption.IGNORE_CASE),
        Regex("ghp_[A-Za-z0-9]{36,}", RegexOption.IGNORE_CASE),
        Regex("github_pat_[A-Za-z0-9_]{20,}", RegexOption.IGNORE_CASE),
        Regex("key-[A-Za-z0-9]{20,}", RegexOption.IGNORE_CASE),
        Regex("[A-Za-z0-9\\-_]{32,}\\.[A-Za-z0-9\\-_]{6,}\\.[A-Za-z0-9\\-_]{20,}"),
    )

    fun sanitize(input: String): String {
        var out = input
        for (pattern in SECRET_PATTERNS) {
            out = pattern.replace(out) { match ->
                val full = match.value
                val prefix = full.take(8)
                val suffix = full.takeLast(4)
                "$prefix****$suffix"
            }
        }
        return out
    }
}
