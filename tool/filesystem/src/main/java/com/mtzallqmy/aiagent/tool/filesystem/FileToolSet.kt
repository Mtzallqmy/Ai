package com.mtzallqmy.aiagent.tool.filesystem

import android.content.Context
import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import com.mtzallqmy.aiagent.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Real filesystem tools: file.read, file.write, file.append, file.list,
 * file.info, file.delete. All paths are resolved inside the current
 * workspace; path traversal is rejected by WorkspaceManager.
 */
class FileToolSet(
    context: Context,
    private val workspace: WorkspaceManager = WorkspaceManager(context),
) {
    val tools: List<AgentTool<Any, Any>> = listOf(
        FileReadTool(workspace),
        FileWriteTool(workspace),
        FileAppendTool(workspace),
        FileListTool(workspace),
        FileInfoTool(workspace),
        FileDeleteTool(workspace),
    )
}

private abstract class FileBaseTool(
    override val descriptor: ToolDescriptor,
    protected val workspace: WorkspaceManager,
) : AgentTool<Any, Any> {

    override suspend fun availability(context: ToolContext): ToolAvailability {
        return try {
            val dir = workspace.workspace(context.workspaceId)
            if (dir.exists()) ToolAvailability.Available
            else ToolAvailability.Unavailable("Workspace ${context.workspaceId} not available")
        } catch (e: Throwable) {
            ToolAvailability.Unavailable(e.message ?: "Workspace unavailable")
        }
    }

    protected fun pathOf(input: Any): String =
        (input as? JsonObject)?.get("path")?.jsonPrimitive?.content
            ?: (input as? String)?.ifBlank { null }
            ?: error("path required")

    protected fun jsonArgs(input: Any): JsonObject =
        input as? JsonObject ?: JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive(input.toString())))

    suspend fun realExecute(workspaceId: String, body: suspend () -> String): ToolResultEnvelope {
        val start = System.currentTimeMillis()
        return try {
            val out = withContext(Dispatchers.IO) { body() }
            ToolResultEnvelope(
                toolId = descriptor.id, success = true, data = out,
                durationMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ToolResultEnvelope(
                toolId = descriptor.id, success = false, data = "",
                error = e.message ?: "IO error", durationMs = 0,
                isRetryable = false, errorCategory = ToolErrorCategory.GENERIC,
            )
        }
    }
}

private class FileReadTool(workspace: WorkspaceManager) : FileBaseTool(
    ToolDescriptor(
        id = "file.read", displayName = "Read File", description = "Read the content of a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: Any, context: ToolContext): Any {
        val path = pathOf(input)
        return realExecute(context.workspaceId) { workspace.readFile(context.workspaceId, path) }
    }
}

private class FileWriteTool(workspace: WorkspaceManager) : FileBaseTool(
    ToolDescriptor(
        id = "file.write", displayName = "Write File", description = "Write content to a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path","content"],"properties":{"path":{"type":"string"},"content":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.MODIFY,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 15_000L,
    ), workspace
) {
    override suspend fun execute(input: Any, context: ToolContext): Any {
        val args = jsonArgs(input)
        val path = pathOf(input)
        val content = args["content"]?.jsonPrimitive?.content ?: error("content required")
        return realExecute(context.workspaceId) {
            workspace.writeFile(context.workspaceId, path, content)
            "Wrote ${content.length} chars to $path"
        }
    }
}

private class FileAppendTool(workspace: WorkspaceManager) : FileBaseTool(
    ToolDescriptor(
        id = "file.append", displayName = "Append File", description = "Append content to a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path","content"],"properties":{"path":{"type":"string"},"content":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.MODIFY,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 15_000L,
    ), workspace
) {
    override suspend fun execute(input: Any, context: ToolContext): Any {
        val args = jsonArgs(input)
        val path = pathOf(input)
        val content = args["content"]?.jsonPrimitive?.content ?: error("content required")
        return realExecute(context.workspaceId) {
            workspace.appendFile(context.workspaceId, path, content)
            "Appended ${content.length} chars to $path"
        }
    }
}

private class FileListTool(workspace: WorkspaceManager) : FileBaseTool(
    ToolDescriptor(
        id = "file.list", displayName = "List Files", description = "List files in a workspace directory",
        inputSchema = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: Any, context: ToolContext): Any {
        val path = pathOf(input).ifEmpty { "." }
        return realExecute(context.workspaceId) {
            workspace.listFiles(context.workspaceId, path).joinToString("\n").ifBlank { "(empty directory)" }
        }
    }
}

private class FileInfoTool(workspace: WorkspaceManager) : FileBaseTool(
    ToolDescriptor(
        id = "file.info", displayName = "File Info", description = "Get file metadata (size, last modified, type)",
        inputSchema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: Any, context: ToolContext): Any {
        val path = pathOf(input)
        return realExecute(context.workspaceId) {
            val file = workspace.workspace(context.workspaceId).resolve(path)
            buildString {
                appendLine("name=${file.name}")
                appendLine("isDirectory=${file.isDirectory}")
                appendLine("length=${file.length()}")
                appendLine("lastModified=${file.lastModified()}")
            }
        }
    }
}

private class FileDeleteTool(workspace: WorkspaceManager) : FileBaseTool(
    ToolDescriptor(
        id = "file.delete", displayName = "Delete File", description = "Delete a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.SYSTEM_SENSITIVE,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: Any, context: ToolContext): Any {
        val path = pathOf(input)
        return realExecute(context.workspaceId) {
            val file = workspace.workspace(context.workspaceId).resolve(path)
            "deleted=${file.deleteRecursively()} path=$path"
        }
    }
}
