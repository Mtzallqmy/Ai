package com.mtzallqmy.aiagent.tool.mcp

import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.model.ApprovalOption
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.tools.ApprovalEngine
import com.mtzallqmy.aiagent.tools.ToolContext
import com.mtzallqmy.aiagent.tools.ToolRuntime
import com.mtzallqmy.aiagent.tools.TypedToolRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRuntimeTest {
    @Test
    fun `MCP tool call cannot bypass ToolRuntime approval`() = runBlocking {
        val registry = TypedToolRegistry()
        val transport = ScriptedTransport()
        val mcp = McpRuntime(registry) { _, _ -> McpClient(transport) }
        val connection = mcp.connect(configuration())
        val registered = requireNotNull(registry.get(connection.toolIds.single()))
        val approval = ApprovalEngine(policyProvider = { ApprovalPolicy.ASK_EVERY_TIME })
        val execution = async {
            ToolRuntime(CapabilityRegistry(), approval).execute(
                tool = registered,
                input = "{}",
                context = ToolContext("run", "workspace"),
                runId = "run",
                agentId = "mcp-agent",
            )
        }

        val request = approval.requests.receive()
        assertEquals(0, transport.toolCalls.get())
        assertFalse(execution.isCompleted)
        approval.respond(request.id, ApprovalOption.ALLOW_ONCE)

        assertTrue(execution.await().success)
        assertEquals(1, transport.toolCalls.get())
        assertNull(registry.get("mcp.server.denied"))
    }

    @Test
    fun `server and content permissions are deny by default`() = runBlocking {
        val registry = TypedToolRegistry()
        val runtime = McpRuntime(registry) { _, _ -> McpClient(ScriptedTransport()) }
        val disabled = configuration().copy(permissions = McpServerPermissions())
        runCatching { runtime.connect(disabled) }.onSuccess { error("Disabled server connected") }

        runtime.connect(configuration())
        assertTrue(runCatching { runtime.listResources("server") }.isFailure)
        assertTrue(runCatching { runtime.listPrompts("server") }.isFailure)
    }

    @Test
    fun `client reconnects session and follows bounded pagination`() = runBlocking {
        val transport = ScriptedTransport(failFirstToolsList = true, paginate = true)
        val client = McpClient(transport)

        assertTrue(client.initialize())
        val tools = client.listTools()

        assertEquals(listOf("allowed", "denied"), tools.map { it.name })
        assertTrue(transport.initializeCalls.get() >= 2)
        assertTrue(transport.closedSessions.get() >= 1)
    }

    @Test
    fun `malformed JSON RPC response is rejected`() = runBlocking {
        val client = McpClient(object : McpTransport {
            override suspend fun send(payload: String, sessionId: String?) =
                McpTransportResponse(200, "not-json", sessionId)
            override suspend fun closeSession(sessionId: String?) = Unit
        })

        val failure = runCatching { client.initialize() }.exceptionOrNull()

        assertTrue(failure is McpProtocolException.InvalidResponse)
    }

    @Test
    fun `authentication rejection is surfaced as permission denied`() = runBlocking {
        val client = McpClient(object : McpTransport {
            override suspend fun send(payload: String, sessionId: String?) =
                McpTransportResponse(403, "", sessionId)
            override suspend fun closeSession(sessionId: String?) = Unit
        })

        val failure = runCatching { client.initialize() }.exceptionOrNull()

        assertTrue(failure is McpProtocolException.PermissionDenied)
    }

    @Test
    fun `OAuth authorization uses PKCE S256 and unique state`() = runBlocking {
        val oauth = oauthClient(RecordingTokenStore())

        val first = oauth.beginAuthorization()
        val second = oauth.beginAuthorization()

        assertTrue(first.authorizationUrl.contains("code_challenge_method=S256"))
        assertTrue(first.authorizationUrl.contains("state="))
        assertFalse(first.state == second.state)
    }

    @Test
    fun `OAuth state mismatch is rejected before token exchange`() = runBlocking {
        val store = RecordingTokenStore()
        val oauth = oauthClient(store)
        oauth.beginAuthorization()

        val failure = runCatching {
            oauth.completeAuthorization("authorization-code", "wrong-state")
        }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertNull(store.saved)
    }

    @Test
    fun `expired OAuth token without refresh token is cleared`() = runBlocking {
        val store = RecordingTokenStore().apply {
            loaded = McpOAuthTokens(
                accessToken = "expired-access",
                tokenType = "Bearer",
                expiresAtMillis = 1_000L,
                refreshToken = null,
                scope = "mcp:tools",
            )
        }
        val oauth = oauthClient(store, clock = { 120_000L })

        val headers = oauth.authorizationHeaders()

        assertTrue(headers.isEmpty())
        assertTrue(store.cleared.get())
    }

    private fun oauthClient(
        store: McpOAuthTokenStore,
        clock: () -> Long = System::currentTimeMillis,
    ) = McpOAuthClient(
        McpOAuthConfiguration(
            serverId = "server",
            authorizationEndpoint = "https://auth.example.test/authorize",
            tokenEndpoint = "https://auth.example.test/token",
            clientId = "aegis",
            redirectUri = "aegis://oauth/mcp",
            scopes = setOf("mcp:tools", "mcp:resources"),
        ),
        store,
        clock,
    )

    private fun configuration() = McpServerConfiguration(
        serverId = "server",
        endpoint = "https://mcp.example.test/rpc",
        permissions = McpServerPermissions(
            enabled = true,
            allowedTools = mapOf("allowed" to RiskLevel.MODIFY),
            resourcesAllowed = false,
            promptsAllowed = false,
        ),
    )

    private class RecordingTokenStore : McpOAuthTokenStore {
        var loaded: McpOAuthTokens? = null
        var saved: McpOAuthTokens? = null
        val cleared = AtomicBoolean(false)

        override fun load(serverId: String): McpOAuthTokens? = loaded
        override fun save(serverId: String, tokens: McpOAuthTokens) {
            saved = tokens
            loaded = tokens
        }
        override fun clear(serverId: String) {
            cleared.set(true)
            loaded = null
        }
    }

    private class ScriptedTransport(
        private val failFirstToolsList: Boolean = false,
        private val paginate: Boolean = false,
    ) : McpTransport {
        private val json = Json
        val toolCalls = AtomicInteger()
        val initializeCalls = AtomicInteger()
        val closedSessions = AtomicInteger()
        private val listCalls = AtomicInteger()

        override suspend fun send(payload: String, sessionId: String?): McpTransportResponse {
            val request = json.parseToJsonElement(payload).jsonObject
            val method = request["method"]!!.jsonPrimitive.content
            val id = request["id"]?.jsonPrimitive?.content
            if (id == null) return McpTransportResponse(202, "", sessionId ?: "session")
            if (method == "tools/list" && failFirstToolsList && listCalls.getAndIncrement() == 0) {
                return McpTransportResponse(503, "", sessionId)
            }
            val result = when (method) {
                "initialize" -> {
                    initializeCalls.incrementAndGet()
                    """{"protocolVersion":"2025-03-26","capabilities":{"tools":{},"resources":{},"prompts":{}}}"""
                }
                "ping" -> "{}"
                "tools/list" -> {
                    val hasCursor = (request["params"] as? JsonObject)?.containsKey("cursor") == true
                    when {
                        paginate && !hasCursor ->
                            """{"tools":[${tool("allowed")}],"nextCursor":"page-2"}"""
                        paginate ->
                            """{"tools":[${tool("denied")}]}"""
                        else ->
                            """{"tools":[${tool("allowed")},${tool("denied")}]}"""
                    }
                }
                "tools/call" -> {
                    toolCalls.incrementAndGet()
                    """{"content":[{"type":"text","text":"executed"}],"isError":false}"""
                }
                else -> "{}"
            }
            return McpTransportResponse(
                statusCode = 200,
                body = """{"jsonrpc":"2.0","id":$id,"result":$result}""",
                sessionId = sessionId ?: "session-${initializeCalls.get()}",
            )
        }

        override suspend fun closeSession(sessionId: String?) {
            if (sessionId != null) closedSessions.incrementAndGet()
        }

        private fun tool(name: String) =
            """{"name":"$name","description":"remote","inputSchema":{"type":"object","additionalProperties":false}}"""
    }
}
