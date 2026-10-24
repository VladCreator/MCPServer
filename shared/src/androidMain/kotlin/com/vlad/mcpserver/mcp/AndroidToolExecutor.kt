package com.vlad.mcpserver.mcp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
 * Only tools that can be implemented honestly with the current permissions are
 * advertised. Camera and touch injection need extra user consent and platform
 * services, so they stay unavailable for now.
 */
class AndroidToolExecutor(private val context: Context) : ToolExecutor {

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            when (toolName) {
                "get_device_info" -> deviceInfo()
                "read_file" -> readFile(arguments)
                "create_notification" -> createNotification(arguments)
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
