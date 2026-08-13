package com.mtzallqmy.aiagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtzallqmy.aiagent.tool.terminal.TerminalToolSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalIsolationAndroidTest {
    @Test
    fun allowlistedCommandRunsThroughInstalledIsolatedExecutor() = runBlocking {
        val result = TerminalToolSet().executeCommand("echo isolated-terminal")

        assertEquals(0, result.exitCode)
        assertEquals("isolated-terminal", result.stdout.trim())
    }

    @Test
    fun shellRemainsRejectedEvenWhenIsolatedExecutorIsInstalled() = runBlocking {
        val result = TerminalToolSet().executeCommand("sh -c 'echo unsafe'")

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("not allowed", ignoreCase = true))
    }
}
