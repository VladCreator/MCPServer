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
    val available: Boolean = true,
    val sensitive: Boolean = false,
    val requirement: String? = null
)

/**
 * Result of executing a tool. Protocol-level failures (unknown tool, invalid
 * arguments) are reported as JSON-RPC errors; execution failures are reported
 * inside the result with [isError] = true so the client can show them.
 */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)

interface ToolExecutor {
    suspend fun execute(clientId: String, toolName: String, arguments: JsonObject): ToolResult
}

/**
 * Built-in tool catalogue. Tools that still need a platform feature are kept
 * here but are not advertised in tools/list until they are implemented.
 */
object McpTools {

    // Declared first: tool definitions below read these during initialization.
    val SCROLL_DIRECTIONS: List<String> = listOf("up", "down", "left", "right")

    val GLOBAL_ACTIONS: List<String> =
        listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen")

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
        },
        sensitive = true,
        requirement = "App sandbox only; max 64 KB"
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
        },
        requirement = "Notification permission"
    )

    val takePhoto: ToolDefinition = ToolDefinition(
        name = "take_photo",
        description = "Capture a JPEG photo with the device camera. The MCPServer app must be " +
            "in the foreground while the capture runs.",
        inputSchema = schema(emptyList()) {
            putJsonObject("quality") {
                put("type", "string")
                put("description", "Optional quality hint; currently ignored")
            }
        },
        available = true,
        sensitive = true,
        requirement = "Camera permission; app in foreground"
    )

    val touchTest: ToolDefinition = ToolDefinition(
        name = "touch_test",
        description = "Inject a tap at the given screen coordinates. Requires the user to enable " +
            "the MCP Touch Service in Android accessibility settings.",
        required = listOf("x", "y"),
        inputSchema = schema(listOf("x", "y")) {
            putJsonObject("x") {
                put("type", "integer")
                put("description", "X coordinate in screen pixels")
            }
            putJsonObject("y") {
                put("type", "integer")
                put("description", "Y coordinate in screen pixels")
            }
        },
        available = true,
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val launchApp: ToolDefinition = ToolDefinition(
        name = "launch_app",
        description = "Launch an installed app by its package name (e.g. com.android.settings). " +
            "Only apps with a launcher entry can be opened.",
        required = listOf("package_name"),
        inputSchema = schema(listOf("package_name")) {
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "Android package name of the app to launch")
            }
        },
        sensitive = true,
        requirement = "Foreground, overlay permission or MCP Touch Service"
    )

    private const val SELECTOR_HINT =
        " Target the element with index (from the last get_screen_tree), text, resource_id " +
            "and/or content_desc."

    val getScreenTree: ToolDefinition = ToolDefinition(
        name = "get_screen_tree",
        description = "Return the visible UI of the foreground app as indented text: element " +
            "index, class, text, description, resource id, screen bounds and flags (clickable, " +
            "scrollable, editable...). Cheapest way to see the screen. Requires MCP Touch Service.",
        inputSchema = schema(emptyList()) {
            putJsonObject("max_nodes") {
                put("type", "integer")
                put("description", "Maximum number of elements to return (default 300, max 1000)")
            }
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val takeScreenshot: ToolDefinition = ToolDefinition(
        name = "take_screenshot",
        description = "Capture the current screen as a JPEG image. Use when get_screen_tree is " +
            "empty or not enough (games, maps, canvas). Requires MCP Touch Service.",
        inputSchema = schema(emptyList()) {
            putJsonObject("max_dimension") {
                put("type", "integer")
                put("description", "Longest image side in pixels (default 1280, 320-2400)")
            }
        },
        sensitive = true,
        requirement = "MCP Touch Service; unavailable on secure screens"
    )

    val clickElement: ToolDefinition = ToolDefinition(
        name = "click_element",
        description = "Click (or long-press) a UI element." + SELECTOR_HINT,
        inputSchema = schema(emptyList()) {
            selectorProperties()
            putJsonObject("long_press") {
                put("type", "boolean")
                put("description", "Long-press instead of click (default false)")
            }
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val inputText: ToolDefinition = ToolDefinition(
        name = "input_text",
        description = "Replace the text of an editable field. Without a selector, the currently " +
            "focused field is used. Target the field with index (from the last get_screen_tree), " +
            "match_text, resource_id and/or content_desc.",
        required = listOf("text"),
        inputSchema = schema(listOf("text")) {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to set in the field")
            }
            putJsonObject("submit") {
                put("type", "boolean")
                put("description", "Press the keyboard Enter/action key afterwards (default false)")
            }
            selectorProperties(textKey = "match_text", textDescription = "Match the field by its current visible text")
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val scroll: ToolDefinition = ToolDefinition(
        name = "scroll",
        description = "Scroll to reveal content in the given direction ('down' shows content " +
            "below). Without a selector, the largest scrollable area is used." + SELECTOR_HINT,
        required = listOf("direction"),
        inputSchema = schema(listOf("direction")) {
            putJsonObject("direction") {
                put("type", "string")
                putJsonArray("enum") { SCROLL_DIRECTIONS.forEach { add(it) } }
            }
            selectorProperties()
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val globalAction: ToolDefinition = ToolDefinition(
        name = "global_action",
        description = "Perform a system navigation action. Requires MCP Touch Service.",
        required = listOf("action"),
        inputSchema = schema(listOf("action")) {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { GLOBAL_ACTIONS.forEach { add(it) } }
            }
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val all: List<ToolDefinition> = listOf(
        getDeviceInfo,
        readFile,
        createNotification,
        takePhoto,
        touchTest,
        launchApp,
        getScreenTree,
        takeScreenshot,
        clickElement,
        inputText,
        scroll,
        globalAction
    )

    fun available(): List<ToolDefinition> = all.filter { it.available }

    fun find(name: String): ToolDefinition? = available().firstOrNull { it.name == name }

    fun isSensitive(name: String): Boolean = all.firstOrNull { it.name == name }?.sensitive == true

    private fun kotlinx.serialization.json.JsonObjectBuilder.selectorProperties(
        textKey: String = "text",
        textDescription: String = "Visible text of the element (case-insensitive, partial match)"
    ) {
        putJsonObject("index") {
            put("type", "integer")
            put("description", "Element index from the last get_screen_tree result")
        }
        putJsonObject(textKey) {
            put("type", "string")
            put("description", textDescription)
        }
        putJsonObject("resource_id") {
            put("type", "string")
            put("description", "View id, e.g. 'com.app:id/login' or just 'login'")
        }
        putJsonObject("content_desc") {
            put("type", "string")
            put("description", "Accessibility description (case-insensitive, partial match)")
        }
    }

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
