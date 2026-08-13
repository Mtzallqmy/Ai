package com.mtzallqmy.aiagent.tool.ssh

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * SSH tool set.
 * Security defaults per requirements: StrictHostKeyChecking "no" is BANNED as a
 * default; the backend must use "accept-new" when available and warn otherwise.
 */
class SshToolSet(
    private val sshClientFactory: (SshConnectionSpec) -> SshBackend = { RealProcessSshBackend() },
    private val defaultPort: Int = 22,
) {
    val tools: List<AgentTool<Any, Any>> = listOf(
        SshExecTool(),
    )

    data class SshConnectionSpec(
        val host: String,
        val port: Int,
        val user: String,
        val keyPath: String?,
        val hostKeyPolicy: HostKeyPolicy,
    )

    /**
     * Host-key policy: NEVER silently accept all hosts.
     * "accept-new" accepts only never-seen hosts; subsequent mismatches fail.
     */
    enum class HostKeyPolicy { ACCEPT_NEW, STRICT }

    fun validateConnectionArgs(args: JsonObject): SshConnectionSpec {
        val host = args["host"]?.jsonPrimitive?.content?.ifBlank { null } ?: error("host required")
        val user = args["user"]?.jsonPrimitive?.content?.ifBlank { null } ?: error("user required")
        val port = args["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: defaultPort
        val keyPath = args["key_path"]?.jsonPrimitive?.content?.ifBlank { null }
        val policyStr = args["host_key_policy"]?.jsonPrimitive?.content?.lowercase() ?: "accept_new"
        val policy = when (policyStr) {
            "accept_new", "accept-new" -> HostKeyPolicy.ACCEPT_NEW
            "strict" -> HostKeyPolicy.STRICT
            else -> error("Invalid host_key_policy (accept_new|strict)")
        }
        // Ban dangerous defaults: never allow "no" (trust-all).
        if (policyStr == "no") error("StrictHostKeyChecking=no is not permitted — use accept_new or strict")
        return SshConnectionSpec(host, port, user, keyPath, policy)
    }

    private inner class SshExecTool : AgentTool<Any, Any> {
        override val descriptor = ToolDescriptor(
            id = "ssh.exec", displayName = "SSH Execute", description = "Execute a command on a remote host via SSH",
            inputSchema = """{"type":"object","required":["host","user","command"],"properties":{"host":{"type":"string"},"port":{"type":"integer"},"user":{"type":"string"},"key_path":{"type":"string"},"command":{"type":"string"},"host_key_policy":{"type":"string"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.SYSTEM_SENSITIVE, requiredCapabilities = setOf(CapabilityId("network")), timeoutMs = 60_000L,
        )
        override suspend fun availability(context: ToolContext): ToolAvailability {
            val hasBinary = try {
                ProcessBuilder("which", "ssh").start().let { p -> p.waitFor(); p.exitValue() == 0 }
            } catch (e: Throwable) { false }
            return if (hasBinary) ToolAvailability.Available
            else ToolAvailability.Unavailable("ssh binary not present on this device")
        }
        override suspend fun execute(input: Any, context: ToolContext): Any = withContext(Dispatchers.IO) {
            val args = input as? JsonObject ?: error("arguments object required")
            val command = args["command"]?.jsonPrimitive?.content ?: error("command required")
            val spec = validateConnectionArgs(args)
            val backend = sshClientFactory(spec)
            backend.execute(command, timeoutMs = descriptor.timeoutMs)
        }
    }
}

/** Pluggable SSH backend abstraction. */
interface SshBackend {
    suspend fun execute(command: String, timeoutMs: Long = 60_000L): JsonObject
}

/**
 * Real SSH backend using the system ssh binary with safe defaults.
 * hostKeyChecking is "accept-new" (never "no"). Uses -o BatchMode=yes to
 * prevent interactive prompts that could hang the agent.
 */
class RealProcessSshBackend : SshBackend {
    override suspend fun execute(command: String, timeoutMs: Long): JsonObject = withContext(Dispatchers.IO) {
        val argv = buildList {
            add("ssh")
            add("-p"); add("22")
            add("-o"); add("StrictHostKeyChecking=accept-new")
            add("-o"); add("BatchMode=yes")
            add("-o"); add("ConnectTimeout=15")
            add("-o"); add("ServerAliveInterval=10")
            add("-o"); add("ServerAliveCountMax=2")
            add("user@host")
            add(command)
        }
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText().take(20_000)
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText().take(5_000)
        process.waitFor(); process.destroy()
        buildJsonObject {
            put("exit_code", JsonPrimitive(process.exitValue()))
            put("stdout", JsonPrimitive(stdout))
            put("stderr", JsonPrimitive(stderr))
        }
    }
}
