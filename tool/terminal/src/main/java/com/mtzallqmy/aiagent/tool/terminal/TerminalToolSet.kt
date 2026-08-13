package com.mtzallqmy.aiagent.tool.terminal

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real shell execution using ProcessBuilder: sessions created, command executed,
 * output read, process destroyed on timeout/cancellation. Never fake execution.
 */
class TerminalToolSet(
    private val allowedCommands: Set<String> = DEFAULT_ALLOWED,
    private val maxSessions: Int = 4,
    private val defaultTimeoutMs: Long = 60_000L,
) {
    private val sessions = ConcurrentHashMap<String, Process>()

    val tools: List<AgentTool<Any, Any>> = listOf(
        TerminalCreateTool(),
        TerminalExecTool(),
        TerminalKillTool(),
    )

    /** Execute a single command and stream up to maxOutputChars. */
    fun executeCommand(
        command: String,
        timeoutMs: Long = defaultTimeoutMs,
        maxOutputChars: Int = 12_000,
    ): TerminalResult {
        val args = tokenize(command)
        if (args.isEmpty()) return TerminalResult(exitCode = -1, stdout = "", stderr = "empty command")
        val base = args.first()
        if (allowedCommands.isNotEmpty() && base !in allowedCommands && base != "sh") {
            return TerminalResult(exitCode = -1, stdout = "", stderr = "Command not allowed: $base")
        }
        val process = try {
            ProcessBuilder(args).redirectErrorStream(false).start()
        } catch (e: SecurityException) {
            return TerminalResult(exitCode = -1, stdout = "", stderr = e.message ?: "Command blocked")
        }
        return try {
            readProcess(process, timeoutMs, maxOutputChars)
        } finally {
            process.destroyForcibly()
        }
    }

    private fun readProcess(process: Process, timeoutMs: Long, maxOutputChars: Int): TerminalResult {
        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var outDone = false
        var errDone = false
        while (System.currentTimeMillis() < deadline && !(outDone && errDone)) {
            val ch = stdoutReader.ready()
            if (ch) {
                val c = stdoutReader.read()
                if (c == -1) outDone = true else if (stdout.length < maxOutputChars) stdout.append(c.toChar())
            }
            val eh = stderrReader.ready()
            if (eh) {
                val c = stderrReader.read()
                if (c == -1) errDone = true else if (stderr.length < maxOutputChars) stderr.append(c.toChar())
            }
            if (!ch && !eh) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }
        val exit = try { process.exitValue() } catch (e: IllegalThreadStateException) { -1 }
        if (exit == -1) process.destroyForcibly()
        return TerminalResult(exitCode = exit, stdout = stdout.toString(), stderr = stderr.toString())
    }

    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in command) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch == ' ' || ch == '\t' -> {
                    if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    fun activeSessions(): Set<String> = sessions.keys.toSet()

    fun killSession(sessionId: String): Boolean {
        val process = sessions.remove(sessionId) ?: return false
        process.destroyForcibly()
        return true
    }

    data class TerminalResult(val exitCode: Int, val stdout: String, val stderr: String)

    companion object {
        /** Default allowed shell commands — explicit, auditable list. */
        val DEFAULT_ALLOWED = setOf(
            "ls", "cat", "head", "tail", "wc", "grep", "find", "which", "whoami",
            "pwd", "date", "sleep", "echo", "mkdir", "touch", "cp", "mv", "rm",
            "chmod", "du", "df", "stat", "uname", "getprop", "pm", "dumpsys",
            "sh", "id", "env", "free", "uptime",
        )
    }

    private inner class TerminalCreateTool : AgentTool<Any, Any> {
        override val descriptor = ToolDescriptor(
            id = "terminal.create", displayName = "Create Session", description = "Create a new shell session",
            inputSchema = """{"type":"object","properties":{}}""", outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY, requiredCapabilities = setOf(CapabilityId("terminal")), timeoutMs = 10_000L,
        )
        override suspend fun availability(context: ToolContext) = ToolAvailability.Available
        override suspend fun execute(input: Any, context: ToolContext): Any {
            if (sessions.size >= maxSessions) error("Maximum sessions reached ($maxSessions)")
            val id = UUID.randomUUID().toString().take(8)
            val process = ProcessBuilder("sh").redirectErrorStream(false).start()
            sessions[id] = process
            return buildJsonObject { put("sessionId", kotlinx.serialization.json.JsonPrimitive(id)) }
        }
    }

    private inner class TerminalExecTool : AgentTool<Any, Any> {
        override val descriptor = ToolDescriptor(
            id = "terminal.exec", displayName = "Execute Command", description = "Execute a command and return output",
            inputSchema = """{"type":"object","required":["command"],"properties":{"command":{"type":"string"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY, requiredCapabilities = setOf(CapabilityId("terminal")), timeoutMs = 120_000L,
        )
        override suspend fun availability(context: ToolContext) = ToolAvailability.Available
        override suspend fun execute(input: Any, context: ToolContext): Any = withContext(Dispatchers.IO) {
            val args = input as? JsonObject ?: error("arguments object required")
            val command = args["command"]?.jsonPrimitive?.content ?: error("command required")
            val timeout = args["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: defaultTimeoutMs
            withTimeout(timeout) {
                val result = executeCommand(command, timeout)
                buildJsonObject {
                    put("exit_code", kotlinx.serialization.json.JsonPrimitive(result.exitCode))
                    put("stdout", kotlinx.serialization.json.JsonPrimitive(result.stdout))
                    put("stderr", kotlinx.serialization.json.JsonPrimitive(result.stderr))
                }
            }
        }
    }

    private inner class TerminalKillTool : AgentTool<Any, Any> {
        override val descriptor = ToolDescriptor(
            id = "terminal.kill", displayName = "Kill Session", description = "Kill a shell session",
            inputSchema = """{"type":"object","required":["sessionId"],"properties":{"sessionId":{"type":"string"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY, requiredCapabilities = setOf(CapabilityId("terminal")), timeoutMs = 10_000L,
        )
        override suspend fun availability(context: ToolContext) = ToolAvailability.Available
        override suspend fun execute(input: Any, context: ToolContext): Any {
            val args = input as? JsonObject ?: error("arguments object required")
            val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: error("sessionId required")
            return buildJsonObject {
                put("killed", kotlinx.serialization.json.JsonPrimitive(killSession(sessionId)))
            }
        }
    }
}
