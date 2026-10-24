package com.vlad.mcpserver.mcp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.vlad.mcpserver.McpTouchAccessibilityService
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

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024
        const val CHANNEL_ID = "mcp_tools"
    }
}
