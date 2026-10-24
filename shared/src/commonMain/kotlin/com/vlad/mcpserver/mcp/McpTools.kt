package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema description of a tool exposed through MCP.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val required: List<String> = emptyList(),
    val available: Boolean = true
)

/**
 * Result of executing a tool. Protocol-level failures (unknown tool, invalid
 * arguments) are reported as JSON-RPC errors; execution failures are reported
 * inside the result with [isError] = true so the client can show them.
 */
data class ToolResult(
    val text: String,
    val isError: Boolean = false
)

interface ToolExecutor {
    suspend fun execute(toolName: String, arguments: JsonObject): ToolResult
}

/**
 * Built-in tool catalogue. Tools that still need a platform feature are kept
 * here but are not advertised in tools/list until they are implemented.
 */
object McpTools {

    val getDeviceInfo: ToolDefinition = ToolDefinition(
        name = "get_device_info",
        description = "Return basic information about the Android device and the MCP server.",
        inputSchema = schema(emptyList()) { }
    )

    val readFile: ToolDefinition = ToolDefinition(
        name = "read_file",
        description = "Read a UTF-8 text file from the app's private storage. " +
            "Only files inside the app's files, cache or external files directories are allowed.",
        required = listOf("path"),
        inputSchema = schema(listOf("path")) {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path inside the app sandbox")
            }
        }
    )

    val createNotification: ToolDefinition = ToolDefinition(
        name = "create_notification",
        description = "Post a system notification on the Android device.",
        required = listOf("title"),
        inputSchema = schema(listOf("title")) {
            putJsonObject("title") {
                put("type", "string")
                put("description", "Notification title")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Notification body")
            }
        }
    )

    val takePhoto: ToolDefinition = ToolDefinition(
        name = "take_photo",
        description = "Capture a photo with the device camera (not enabled yet).",
        inputSchema = schema(emptyList()) {
            putJsonObject("quality") { put("type", "string") }
        },
        available = false
    )

    val touchTest: ToolDefinition = ToolDefinition(
        name = "touch_test",
        description = "Inject a tap on the device screen (requires the accessibility service, not enabled yet).",
        required = listOf("x", "y"),
        inputSchema = schema(listOf("x", "y")) {
            putJsonObject("x") { put("type", "integer") }
            putJsonObject("y") { put("type", "integer") }
        },
        available = false
    )

    val all: List<ToolDefinition> = listOf(
        getDeviceInfo,
        readFile,
        createNotification,
        takePhoto,
        touchTest
    )

    fun available(): List<ToolDefinition> = all.filter { it.available }

    fun find(name: String): ToolDefinition? = available().firstOrNull { it.name == name }

    private fun schema(
        required: List<String>,
        properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties() }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
        put("additionalProperties", false)
    }
}
