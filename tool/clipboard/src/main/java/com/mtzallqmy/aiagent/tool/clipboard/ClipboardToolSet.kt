package com.mtzallqmy.aiagent.tool.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Real clipboard read/write tools. */
class ClipboardToolSet(private val context: Context) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    val tools: List<AgentTool<Any, Any>> = listOf(
        ClipboardReadTool(),
        ClipboardWriteTool(),
    )

    private inner class ClipboardReadTool : AgentTool<Any, Any> {
        override val descriptor = ToolDescriptor(
            id = "clipboard.read", displayName = "Read Clipboard", description = "Read the current clipboard text",
            inputSchema = """{"type":"object","properties":{}}""", outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.READ, requiredCapabilities = setOf(CapabilityId("clipboard")), timeoutMs = 5_000L,
        )
        override suspend fun availability(toolContext: ToolContext): ToolAvailability =
            if (clipboard != null && clipboard.hasPrimaryClip()) ToolAvailability.Available
            else ToolAvailability.Unavailable("Clipboard empty or unavailable")
        override suspend fun execute(input: Any, toolContext: ToolContext): Any {
            val clip = clipboard?.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(this@ClipboardToolSet.context)?.toString() ?: ""
            return buildJsonObject { put("text", JsonPrimitive(text)) }
        }
    }

    private inner class ClipboardWriteTool : AgentTool<Any, Any> {
        override val descriptor = ToolDescriptor(
            id = "clipboard.write", displayName = "Write Clipboard", description = "Copy text to the clipboard",
            inputSchema = """{"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY, requiredCapabilities = setOf(CapabilityId("clipboard")), timeoutMs = 5_000L,
        )
        override suspend fun availability(toolContext: ToolContext): ToolAvailability =
            if (clipboard != null) ToolAvailability.Available
            else ToolAvailability.Unavailable("Clipboard service unavailable")
        override suspend fun execute(input: Any, toolContext: ToolContext): Any {
            val args = input as? JsonObject ?: error("arguments required")
            val text = args["text"]?.jsonPrimitive?.content ?: error("text required")
            val clip = ClipData.newPlainText("aegis-agent", text)
            clipboard?.setPrimaryClip(clip)
            return buildJsonObject { put("written", JsonPrimitive(true)); put("length", JsonPrimitive(text.length)) }
        }
    }
}
