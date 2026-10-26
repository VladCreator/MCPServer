package com.vlad.mcpserver.mcp

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.vlad.mcpserver.McpTouchAccessibilityService
import com.vlad.mcpserver.NodeSelector
import com.vlad.mcpserver.ScreenNavigator
import com.vlad.mcpserver.AppVisibility
import com.vlad.mcpserver.CameraCapture
import com.vlad.mcpserver.getLocalIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Android implementation of the device tools.
 *
 * Sensitive tools (file reads, camera and touch injection) go through the
 * consent gate supplied by the server host, which asks the user in the
 * dashboard before executing them.
 */
class AndroidToolExecutor(
    private val context: Context,
    private val consent: suspend (clientId: String, toolName: String, arguments: JsonObject) -> Boolean =
        { _, _, _ -> true }
) : ToolExecutor {

    override suspend fun execute(
        clientId: String,
        toolName: String,
        arguments: JsonObject
    ): ToolResult = withContext(Dispatchers.IO) {
            if (McpTools.isSensitive(toolName) && !consent(clientId, toolName, arguments)) {
                return@withContext ToolResult("User rejected the tool call.", isError = true)
            }
            when (toolName) {
                "get_device_info" -> deviceInfo()
                "read_file" -> readFile(arguments)
                "create_notification" -> createNotification(arguments)
                "touch_test" -> tap(arguments)
                "take_photo" -> takePhoto()
                "launch_app" -> launchApp(arguments)
                "get_screen_tree" -> screenTree(arguments)
                "take_screenshot" -> screenshot(arguments)
                "click_element" -> clickElement(arguments)
                "input_text" -> inputText(arguments)
                "scroll" -> scroll(arguments)
                "global_action" -> globalAction(arguments)
                else -> ToolResult("Tool '$toolName' is not available.", isError = true)
            }
        }

    private fun deviceInfo(): ToolResult {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val text = buildString {
            appendLine("Platform: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("App: ${context.packageName} $versionName")
            append("Local IP: ${getLocalIpAddress()}")
        }
        return ToolResult(text)
    }

    private fun readFile(arguments: JsonObject): ToolResult {
        val rawPath = (arguments["path"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult("Missing required argument: path", isError = true)
        if (rawPath.isEmpty()) {
            return ToolResult("Missing required argument: path", isError = true)
        }

        val file = runCatching { File(rawPath).canonicalFile }.getOrElse {
            return ToolResult("Invalid path: ${it.message}", isError = true)
        }
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null)
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }

        val allowed = roots.any { root ->
            file.path == root.path || file.path.startsWith(root.path + File.separator)
        }
        if (!allowed) {
            return ToolResult(
                "Path is outside the app sandbox. Allowed roots: " +
                    roots.joinToString(", ") { it.path },
                isError = true
            )
        }
        if (!file.isFile) {
            return ToolResult("File not found: ${file.path}", isError = true)
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult("File is too large (max ${MAX_FILE_BYTES / 1024} KB): ${file.path}", isError = true)
        }

        val bytes = runCatching { file.readBytes() }.getOrElse {
            return ToolResult("Unable to read ${file.path}: ${it.message}", isError = true)
        }
        if (bytes.any { it == 0.toByte() }) {
            return ToolResult("Binary files are not supported: ${file.path}", isError = true)
        }

        val text = bytes.toString(Charsets.UTF_8)
        return ToolResult("File: ${file.path} (${bytes.size} bytes)\n\n$text")
    }

    private fun createNotification(arguments: JsonObject): ToolResult {
        if (!hasNotificationPermission()) {
            return ToolResult(
                "Notification permission is not granted. Enable notifications for MCPServer in Android settings.",
                isError = true
            )
        }
        val title = (arguments["title"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.take(100)
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("Missing required argument: title", isError = true)
        val message = (arguments["message"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.take(500)
            .orEmpty()

        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return ToolResult("NotificationManager is unavailable", isError = true)
            val channelId = CHANNEL_ID
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "MCP tool notifications",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val builder = Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setAutoCancel(true)
            if (message.isNotEmpty()) {
                builder.setContentText(message)
                builder.setStyle(Notification.BigTextStyle().bigText(message))
            }
            val id = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            manager.notify(id, builder.build())
            ToolResult("Notification posted with id $id: $title")
        }.getOrElse { error ->
            ToolResult("Unable to post notification: ${error.message}", isError = true)
        }
    }

    private suspend fun takePhoto(): ToolResult {
        if (!AppVisibility.isForeground) {
            return ToolResult(
                "Camera capture is only allowed while the MCPServer app is in the foreground. " +
                    "Open the app and try again.",
                isError = true
            )
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(
                "Camera permission is not granted. Enable it in Android settings and try again.",
                isError = true
            )
        }
        val captured = CameraCapture(context).capture()
        return captured.fold(
            onSuccess = { file ->
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null) {
                    ToolResult("Photo saved to ${file.absolutePath} but could not be read.", isError = true)
                } else {
                    ToolResult(
                        text = "Photo captured: ${file.absolutePath} (${bytes.size} bytes)",
                        imageBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
                        imageMimeType = "image/jpeg"
                    )
                }
            },
            onFailure = { error ->
                ToolResult(
                    "Camera capture failed: ${error.message ?: error::class.simpleName}",
                    isError = true
                )
            }
        )
    }

    private suspend fun tap(arguments: JsonObject): ToolResult {
        val service = McpTouchAccessibilityService.instance()
            ?: return ToolResult(
                "Touch injection is disabled. Enable 'MCP Touch Service' in " +
                    "Android Settings > Accessibility, then try again.",
                isError = true
            )
        val x = (arguments["x"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
        val y = (arguments["y"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
        if (x == null || y == null) {
            return ToolResult("Arguments x and y are required.", isError = true)
        }
        val metrics = context.resources.displayMetrics
        if (x < 0f || y < 0f || x >= metrics.widthPixels || y >= metrics.heightPixels) {
            return ToolResult(
                "Coordinates are outside the screen (${metrics.widthPixels}x${metrics.heightPixels}).",
                isError = true
            )
        }
        val dispatched = service.tap(x, y)
        return if (dispatched) {
            ToolResult("Tap dispatched at (${x.toInt()}, ${y.toInt()}).")
        } else {
            ToolResult("The system rejected the gesture.", isError = true)
        }
    }

    private suspend fun launchApp(arguments: JsonObject): ToolResult {
        val packageName = (arguments["package_name"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("Missing required argument: package_name", isError = true)
        if (!PACKAGE_NAME_REGEX.matches(packageName)) {
            return ToolResult("Invalid package name: $packageName", isError = true)
        }

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult(
                "App '$packageName' is not installed or has no launcher activity.",
                isError = true
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        // Android blocks background activity starts unless the app is visible, has a
        // bound accessibility service or holds "Display over other apps".
        if (!AppVisibility.isForeground &&
            McpTouchAccessibilityService.instance() == null &&
            !Settings.canDrawOverlays(context)
        ) {
            return ToolResult(
                "Cannot launch apps while MCPServer is in the background. Grant 'Display over " +
                    "other apps' (MCPServer Settings > Device access), enable 'MCP Touch Service' " +
                    "in Accessibility, or open MCPServer, then try again.",
                isError = true
            )
        }

        return withContext(Dispatchers.Main) {
            runCatching {
                context.startActivity(intent)
                ToolResult("Launched $packageName.")
            }.getOrElse { error ->
                ToolResult("Unable to launch $packageName: ${error.message}", isError = true)
            }
        }
    }

    private fun touchService(): McpTouchAccessibilityService? = McpTouchAccessibilityService.instance()

    private fun touchServiceMissing(): ToolResult = ToolResult(
        "This tool needs 'MCP Touch Service'. Enable it in Android Settings > Accessibility " +
            "(or MCPServer Settings > Device access), then try again.",
        isError = true
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"

    private fun JsonObject.selector(textKey: String = "text"): NodeSelector = NodeSelector(
        index = int("index"),
        text = string(textKey),
        resourceId = string("resource_id"),
        contentDescription = string("content_desc")
    )

    private fun describeNode(node: android.view.accessibility.AccessibilityNodeInfo): String {
        val label = node.text?.takeIf { it.isNotBlank() && !node.isPassword }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: node.viewIdResourceName
            ?: node.className?.toString()?.substringAfterLast('.')
            ?: "element"
        val rect = ScreenNavigator.bounds(node)
        return "'$label' at (${rect.centerX()}, ${rect.centerY()})"
    }

    private fun screenTree(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val maxNodes = (arguments.int("max_nodes") ?: DEFAULT_TREE_NODES).coerceIn(1, MAX_TREE_NODES)
        val tree = ScreenNavigator.dumpTree(service, maxNodes)
            ?: return ToolResult(
                "No active window is available. If the service was just updated, turn " +
                    "'MCP Touch Service' off and on again in Accessibility settings.",
                isError = true
            )
        return ToolResult(tree)
    }

    private suspend fun screenshot(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val maxDimension = (arguments.int("max_dimension") ?: DEFAULT_SCREENSHOT_DIMENSION)
            .coerceIn(MIN_SCREENSHOT_DIMENSION, MAX_SCREENSHOT_DIMENSION)
        return ScreenNavigator.takeScreenshot(service, maxDimension).fold(
            onSuccess = { shot ->
                ToolResult(
                    text = "Screenshot ${shot.width}x${shot.height} (screen " +
                        "${shot.screenWidth}x${shot.screenHeight}). Multiply image coordinates by " +
                        "${"%.3f".format(shot.screenWidth.toFloat() / shot.width)} to get screen " +
                        "coordinates for touch_test.",
                    imageBase64 = java.util.Base64.getEncoder().encodeToString(shot.jpegBytes),
                    imageMimeType = "image/jpeg"
                )
            },
            onFailure = { ToolResult("Screenshot failed: ${it.message}", isError = true) }
        )
    }

    private suspend fun clickElement(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val selector = arguments.selector()
        if (selector.isEmpty) {
            return ToolResult("Provide index, text, resource_id or content_desc.", isError = true)
        }
        val node = ScreenNavigator.find(service, selector)
            ?: return ToolResult(
                "No visible element matches $selector. Call get_screen_tree to refresh.",
                isError = true
            )
        val longPress = arguments.bool("long_press")
        val description = describeNode(node)
        return if (ScreenNavigator.click(service, node, longPress)) {
            ToolResult("${if (longPress) "Long-pressed" else "Clicked"} $description.")
        } else {
            ToolResult("Unable to click $description.", isError = true)
        }
    }

    private fun inputText(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val text = (arguments["text"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult("Missing required argument: text", isError = true)
        if (text.length > MAX_INPUT_CHARS) {
            return ToolResult("Text is too long (max $MAX_INPUT_CHARS characters).", isError = true)
        }
        val selector = arguments.selector(textKey = "match_text")
        val matched = if (selector.isEmpty) {
            null
        } else {
            ScreenNavigator.find(service, selector)
                ?: return ToolResult("No visible element matches $selector.", isError = true)
        }
        val field = ScreenNavigator.editableTarget(service, matched)
            ?: return ToolResult(
                if (selector.isEmpty) {
                    "No focused text field. Pass a selector or click the field first."
                } else {
                    "The matched element is not an editable field."
                },
                isError = true
            )
        if (!ScreenNavigator.setText(field, text)) {
            return ToolResult("The field rejected the text.", isError = true)
        }
        val submitted = arguments.bool("submit") && ScreenNavigator.pressImeEnter(field)
        return ToolResult(
            "Text entered (${text.length} chars) in ${describeNode(field)}" +
                if (arguments.bool("submit")) {
                    if (submitted) " and submitted." else "; submit is not supported by this field."
                } else {
                    "."
                }
        )
    }

    private suspend fun scroll(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val direction = arguments.string("direction")?.lowercase()
        if (direction !in McpTools.SCROLL_DIRECTIONS) {
            return ToolResult(
                "Argument direction must be one of ${McpTools.SCROLL_DIRECTIONS}.",
                isError = true
            )
        }
        val selector = arguments.selector()
        val node = if (selector.isEmpty) {
            ScreenNavigator.primaryScrollable(service)
        } else {
            ScreenNavigator.find(service, selector)
                ?: return ToolResult("No visible element matches $selector.", isError = true)
        }
        val method = ScreenNavigator.scroll(service, node, direction!!)
            ?: return ToolResult("Unable to scroll $direction.", isError = true)
        return ToolResult("Scrolled $direction using $method.")
    }

    private fun globalAction(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val name = arguments.string("action")?.lowercase()
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return ToolResult(
                "Argument action must be one of ${McpTools.GLOBAL_ACTIONS}.",
                isError = true
            )
        }
        return if (service.performGlobalAction(action)) {
            ToolResult("Performed global action '$name'.")
        } else {
            ToolResult("The system rejected global action '$name'.", isError = true)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024
        const val CHANNEL_ID = "mcp_tools"
        const val DEFAULT_TREE_NODES = 300
        const val MAX_TREE_NODES = 1000
        const val DEFAULT_SCREENSHOT_DIMENSION = 1280
        const val MIN_SCREENSHOT_DIMENSION = 320
        const val MAX_SCREENSHOT_DIMENSION = 2400
        const val MAX_INPUT_CHARS = 5000
        val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
