package com.mtzallqmy.aiagent.tool.terminal

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

fun interface TerminalCommandExecutor {
    suspend fun execute(
        argv: List<String>,
        timeoutMs: Long,
        maxOutputBytes: Int,
    ): TerminalToolSet.TerminalResult
}

/**
 * Terminal policy and tool surface. Process creation is delegated to an injected
 * executor so the Android app process never needs to spawn a shell itself.
 */
class TerminalToolSet(
    private val commandExecutor: TerminalCommandExecutor = TerminalCommandExecutor { _, _, _ ->
        TerminalResult(
            exitCode = -1,
            stdout = "",
            stderr = "Isolated terminal backend is not configured",
        )
    },
    private val allowedCommands: Set<String> = DEFAULT_ALLOWED,
    private val maxSessions: Int = 4,
    private val defaultTimeoutMs: Long = 60_000L,
) {
    private val sessions = ConcurrentHashMap.newKeySet<String>()

    val tools: List<RegisteredTool> = listOf(
        RegisteredTool.typed(TerminalCreateTool(), TerminalCreateInput.serializer()),
        RegisteredTool.typed(TerminalExecTool(), TerminalExecInput.serializer()),
        RegisteredTool.typed(TerminalKillTool(), TerminalKillInput.serializer()),
    )

    suspend fun executeCommand(
        command: String,
        timeoutMs: Long = defaultTimeoutMs,
        maxOutputChars: Int = 12_000,
    ): TerminalResult {
        val argv = runCatching { tokenize(command) }.getOrElse { error ->
            return TerminalResult(-1, "", error.message ?: "Invalid command")
        }
        if (argv.isEmpty()) return TerminalResult(-1, "", "empty command")
        val base = argv.first()
        if (base !in allowedCommands) {
            return TerminalResult(-1, "", "Command not allowed: $base")
        }
        val timeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        val outputLimit = maxOutputChars.coerceIn(1, MAX_OUTPUT_BYTES)
        return commandExecutor.execute(argv, timeout, outputLimit)
    }

    private fun tokenize(command: String): List<String> {
        require(command.length <= MAX_COMMAND_CHARS) { "Command is too long" }
        require('\u0000' !in command && '\n' !in command && '\r' !in command) {
            "Command contains forbidden control characters"
        }
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in command) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch == ' ' || ch == '\t' -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        require(quote == null) { "Command contains an unterminated quote" }
        if (current.isNotEmpty()) tokens += current.toString()
        require(tokens.size <= MAX_ARGUMENTS) { "Command has too many arguments" }
        require(tokens.all { it.length <= MAX_ARGUMENT_CHARS }) { "Command argument is too long" }
        return tokens
    }

    fun activeSessions(): Set<String> = sessions.toSet()

    fun killSession(sessionId: String): Boolean = sessions.remove(sessionId)

    data class TerminalResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    companion object {
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_OUTPUT_BYTES = 256 * 1024
        const val MAX_COMMAND_CHARS = 16 * 1024
        const val MAX_ARGUMENTS = 64
        const val MAX_ARGUMENT_CHARS = 4 * 1024

        /** Conservative toybox-compatible applets. Shell execution is intentionally absent. */
        val DEFAULT_ALLOWED = setOf(
            "ls", "cat", "head", "tail", "wc", "grep", "find", "which", "whoami",
            "pwd", "date", "sleep", "echo", "mkdir", "touch", "cp", "mv", "rm",
            "chmod", "du", "df", "stat", "uname", "id", "env", "uptime",
        )
    }

    private inner class TerminalCreateTool : AgentTool<TerminalCreateInput, JsonObject> {
        override val descriptor = ToolDescriptor(
            id = "terminal.create",
            displayName = "Create Session",
            description = "Create a logical isolated-terminal session",
            inputSchema = """{"type":"object","properties":{}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY,
            requiredCapabilities = setOf(CapabilityId("terminal")),
            timeoutMs = 10_000L,
        )

        override suspend fun availability(context: ToolContext) = ToolAvailability.Available

        override suspend fun execute(input: TerminalCreateInput, context: ToolContext): JsonObject {
            if (sessions.size >= maxSessions) error("Maximum sessions reached ($maxSessions)")
            val id = UUID.randomUUID().toString().take(8)
            sessions += id
            return buildJsonObject { put("sessionId", JsonPrimitive(id)) }
        }
    }

    private inner class TerminalExecTool : AgentTool<TerminalExecInput, JsonObject> {
        override val descriptor = ToolDescriptor(
            id = "terminal.exec",
            displayName = "Execute Command",
            description = "Execute an allow-listed command in the isolated Rust runtime",
            inputSchema = """{"type":"object","required":["command"],"properties":{"command":{"type":"string"},"timeout_ms":{"type":"integer","minimum":1,"maximum":120000}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY,
            requiredCapabilities = setOf(CapabilityId("terminal")),
            timeoutMs = MAX_TIMEOUT_MS,
        )

        override suspend fun availability(context: ToolContext) = ToolAvailability.Available

        override suspend fun execute(input: TerminalExecInput, context: ToolContext): JsonObject {
            val timeout = (input.timeoutMs ?: defaultTimeoutMs).coerceIn(1L, descriptor.timeoutMs)
            val result = withTimeout(timeout + 1_000L) {
                executeCommand(input.command, timeout)
            }
            return buildJsonObject {
                put("exit_code", JsonPrimitive(result.exitCode))
                put("stdout", JsonPrimitive(result.stdout))
                put("stderr", JsonPrimitive(result.stderr))
            }
        }
    }

    private inner class TerminalKillTool : AgentTool<TerminalKillInput, JsonObject> {
        override val descriptor = ToolDescriptor(
            id = "terminal.kill",
            displayName = "Kill Session",
            description = "Close a logical isolated-terminal session",
            inputSchema = """{"type":"object","required":["sessionId"],"properties":{"sessionId":{"type":"string"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY,
            requiredCapabilities = setOf(CapabilityId("terminal")),
            timeoutMs = 10_000L,
        )

        override suspend fun availability(context: ToolContext) = ToolAvailability.Available

        override suspend fun execute(input: TerminalKillInput, context: ToolContext): JsonObject =
            buildJsonObject { put("killed", JsonPrimitive(killSession(input.sessionId))) }
    }
}

@Serializable
class TerminalCreateInput

@Serializable
data class TerminalExecInput(
    val command: String,
    @SerialName("timeout_ms") val timeoutMs: Long? = null,
)

@Serializable
data class TerminalKillInput(val sessionId: String)
