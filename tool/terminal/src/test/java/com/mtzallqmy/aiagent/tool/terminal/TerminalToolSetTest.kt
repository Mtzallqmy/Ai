package com.mtzallqmy.aiagent.tool.terminal

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalToolSetTest {
    @Test
    fun `default backend is fail closed`() = runBlocking {
        val result = TerminalToolSet().executeCommand("echo hello")

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("not configured", ignoreCase = true))
    }

    @Test
    fun `shell executable is rejected before executor`() = runBlocking {
        val calls = AtomicInteger()
        val toolSet = TerminalToolSet(
            commandExecutor = TerminalCommandExecutor { _, _, _ ->
                calls.incrementAndGet()
                TerminalToolSet.TerminalResult(0, "unexpected", "")
            },
        )

        val result = toolSet.executeCommand("sh -c 'echo unsafe'")

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("not allowed", ignoreCase = true))
        assertEquals(0, calls.get())
    }

    @Test
    fun `command is forwarded as argv without shell interpretation`() = runBlocking {
        var captured = emptyList<String>()
        val toolSet = TerminalToolSet(
            commandExecutor = TerminalCommandExecutor { argv, _, _ ->
                captured = argv
                TerminalToolSet.TerminalResult(0, "ok", "")
            },
        )

        val result = toolSet.executeCommand("echo 'hello; rm marker'")

        assertEquals(0, result.exitCode)
        assertEquals(listOf("echo", "hello; rm marker"), captured)
    }

    @Test
    fun `timeout and output limits are bounded before executor`() = runBlocking {
        var timeout = 0L
        var limit = 0
        val toolSet = TerminalToolSet(
            commandExecutor = TerminalCommandExecutor { _, timeoutMs, maxOutputBytes ->
                timeout = timeoutMs
                limit = maxOutputBytes
                TerminalToolSet.TerminalResult(0, "", "")
            },
        )

        toolSet.executeCommand("echo ok", timeoutMs = Long.MAX_VALUE, maxOutputChars = Int.MAX_VALUE)

        assertEquals(TerminalToolSet.MAX_TIMEOUT_MS, timeout)
        assertEquals(TerminalToolSet.MAX_OUTPUT_BYTES, limit)
    }
}
