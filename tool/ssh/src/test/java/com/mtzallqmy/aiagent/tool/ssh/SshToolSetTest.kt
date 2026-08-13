package com.mtzallqmy.aiagent.tool.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SshToolSetTest {
    private val tools = SshToolSet()

    @Test
    fun `strict host key verification is default`() {
        val spec = tools.validateConnectionArgs(
            SshExecInput(host = "example.com", user = "alice", command = "true"),
        )

        assertEquals(SshToolSet.HostKeyPolicy.STRICT, spec.hostKeyPolicy)
        assertEquals(22, spec.port)
    }

    @Test
    fun `accept new requires explicit opt in`() {
        val spec = tools.validateConnectionArgs(
            SshExecInput(
                host = "example.com",
                user = "alice",
                command = "true",
                hostKeyPolicy = "accept-new",
            ),
        )

        assertEquals(SshToolSet.HostKeyPolicy.ACCEPT_NEW, spec.hostKeyPolicy)
    }

    @Test
    fun `trust all host key policy is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            tools.validateConnectionArgs(
                SshExecInput(
                    host = "example.com",
                    user = "alice",
                    command = "true",
                    hostKeyPolicy = "no",
                ),
            )
        }
    }

    @Test
    fun `invalid host user and port are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            tools.validateConnectionArgs(SshExecInput("bad host", "alice", "true"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.validateConnectionArgs(SshExecInput("example.com", "bad user", "true"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.validateConnectionArgs(SshExecInput("example.com", "alice", "true", port = 70_000))
        }
    }
}
