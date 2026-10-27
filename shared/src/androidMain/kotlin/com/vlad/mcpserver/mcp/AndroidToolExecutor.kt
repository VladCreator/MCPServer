package com.vlad.mcpserver.mcp

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.vlad.mcpserver.McpTouchAccessibilityService
import com.vlad.mcpserver.NodeSelector
import com.vlad.mcpserver.ScreenNavigator
import com.vlad.mcpserver.Screenshot
import com.vlad.mcpserver.AppVisibility
import com.vlad.mcpserver.CameraCapture
import com.vlad.mcpserver.getLocalIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    private val audio = AndroidAudioController(context)
    private val deviceState = AndroidDeviceStateController(context)
    private val qa = QaSessionManager(context)
    private val startedAt = SystemClock.elapsedRealtime()

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
                "list_apps" -> listApps(arguments)
                "swipe" -> swipe(arguments)
                "get_app_version" -> appVersion(arguments)
                "get_logs" -> logs(arguments)
                "health_check" -> healthCheck()
                "get_screen_tree" -> screenTree(arguments)
                "take_screenshot" -> screenshot(arguments)
                "click_element" -> clickElement(arguments)
                "input_text" -> inputText(arguments)
                "scroll" -> scroll(arguments)
                "global_action" -> globalAction(arguments)
                "qa_session" -> qaSession(arguments)
                "ui_find" -> uiFind(arguments)
                "ui_wait" -> uiWait(arguments)
                "ui_assert" -> uiAssert(arguments)
                "ui_gesture" -> uiGesture(arguments)
                "audio_instruction" -> audioInstruction(arguments)
                "audio_state" -> audio.state()
                "get_device_state" -> ToolResult(deviceState.readText())
                "device_state" -> setDeviceState(arguments)
                "ui_diff" -> uiDiff(arguments)
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
            ToolResult("Tap at (${x.toInt()}, ${y.toInt()}) was not performed: ${tapFailureReason(service)}", isError = true)
        }
    }

    private fun tapFailureReason(service: McpTouchAccessibilityService): String {
        val windows = service.windows
        val active = windows.firstOrNull { it.isActive }
        val root = service.rootInActiveWindow
        val reasons = buildList {
            if (windows.isEmpty()) add("no window is available (screen off, locked or a transition is in progress)")
            else if (active == null) add("no window currently has input focus (transition in progress or system dialog)")
            if (root == null) add("the active window content is unavailable (secure or still loading)")
            if (!context.getSystemService(android.os.PowerManager::class.java).isInteractive) {
                add("the screen is off")
            }
        }
        val detail = if (reasons.isEmpty()) {
            "the system cancelled the gesture (window changed during the tap, or the target is covered by a secure window)"
        } else {
            reasons.joinToString("; ")
        }
        val pkg = root?.packageName?.let { " Foreground package: $it." }.orEmpty()
        return "$detail.$pkg Retry after the UI settles (ui_wait idle)."
    }

    private fun healthCheck(): ToolResult {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val uptimeSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        return ToolResult(
            "ok\ntime=${java.time.Instant.now()}\nuptime_s=$uptimeSeconds\nversion=$versionName" +
                "\ntouch_service=${McpTouchAccessibilityService.instance() != null}"
        )
    }

    private suspend fun swipe(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val direction = arguments.string("direction")?.lowercase()
        val fromX = arguments.float("from_x")
        val fromY = arguments.float("from_y")
        val toX = arguments.float("to_x")
        val toY = arguments.float("to_y")
        val (x1, y1, x2, y2) = if (direction != null) {
            if (direction !in McpTools.SCROLL_DIRECTIONS) {
                return ToolResult("direction must be one of ${McpTools.SCROLL_DIRECTIONS}.", isError = true)
            }
            val d = (arguments.float("distance") ?: 0.6f).coerceIn(0.1f, 0.9f)
            val cx = fromX ?: (w / 2)
            val cy = fromY ?: (h / 2)
            when (direction) {
                "up" -> listOf(cx, cy + h * d / 2, cx, cy - h * d / 2)
                "down" -> listOf(cx, cy - h * d / 2, cx, cy + h * d / 2)
                "left" -> listOf(cx + w * d / 2, cy, cx - w * d / 2, cy)
                else -> listOf(cx - w * d / 2, cy, cx + w * d / 2, cy)
            }
        } else if (fromX != null && fromY != null && toX != null && toY != null) {
            listOf(fromX, fromY, toX, toY)
        } else {
            return ToolResult("Provide direction or from_x, from_y, to_x, to_y.", isError = true)
        }
        if (listOf(x1, x2).any { it < 0f || it >= w } || listOf(y1, y2).any { it < 0f || it >= h }) {
            return ToolResult("Swipe points are outside the screen (${w.toInt()}x${h.toInt()}).", isError = true)
        }
        val duration = (arguments.int("duration_ms") ?: 300).coerceIn(50, 5_000).toLong()
        val count = (arguments.int("count") ?: 1).coerceIn(1, MAX_REPEAT)
        val interval = (arguments.int("interval_ms") ?: DEFAULT_REPEAT_INTERVAL_MS).coerceIn(0, MAX_REPEAT_INTERVAL_MS).toLong()
        var done = 0
        repeat(count) { i ->
            if (!service.swipe(x1, y1, x2, y2, duration)) {
                return ToolResult(
                    "Swipe ${i + 1}/$count was not performed ($done done): ${tapFailureReason(service)}",
                    isError = true
                )
            }
            done++
            if (i < count - 1) delay(interval)
        }
        return ToolResult("Swiped (${x1.toInt()},${y1.toInt()}) -> (${x2.toInt()},${y2.toInt()}) in ${duration}ms x$done.")
    }

    private fun appVersion(arguments: JsonObject): ToolResult {
        val pkg = arguments.string("package_name")
            ?: return ToolResult("Missing required argument: package_name", isError = true)
        if (!PACKAGE_NAME_REGEX.matches(pkg)) return ToolResult("Invalid package name: $pkg", isError = true)
        val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            ?: return ToolResult("App '$pkg' is not installed or not visible.", isError = true)
        return ToolResult(
            "package=$pkg\nversionName=${info.versionName}\nversionCode=${info.longVersionCode}" +
                "\nfirstInstall=${java.time.Instant.ofEpochMilli(info.firstInstallTime)}" +
                "\nlastUpdate=${java.time.Instant.ofEpochMilli(info.lastUpdateTime)}"
        )
    }

    private fun logs(arguments: JsonObject): ToolResult {
        val lines = (arguments.int("lines") ?: 200).coerceIn(1, 1000)
        val level = arguments.string("level")?.uppercase()?.take(1)?.takeIf { it in "VDIWEF" } ?: "I"
        val query = arguments.string("query")
        val pkg = arguments.string("package_name")
        if (pkg != null && !PACKAGE_NAME_REGEX.matches(pkg)) {
            return ToolResult("Invalid package name: $pkg", isError = true)
        }
        val command = mutableListOf("logcat", "-d", "-v", "threadtime", "-b", if (arguments.bool("crashes")) "crash" else "main")
        if (pkg != null) {
            val pids = runCatching {
                ProcessBuilder("pidof", pkg).redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText().trim().split(Regex("\\s+")).filter { it.all(Char::isDigit) && it.isNotEmpty() }
            }.getOrDefault(emptyList())
            if (pids.isEmpty()) {
                return ToolResult("No running process found for '$pkg' (logs are filtered by live PID).", isError = true)
            }
            command += listOf("--pid", pids.first())
        }
        command += "*:$level"
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            val filtered = output.filter { query == null || it.contains(query, ignoreCase = true) }.takeLast(lines)
            if (filtered.isEmpty()) {
                ToolResult("No log lines matched. Other apps' logs require: adb shell pm grant ${context.packageName} android.permission.READ_LOGS")
            } else {
                ToolResult(filtered.joinToString("\n").take(MAX_LOG_CHARS))
            }
        }.getOrElse { ToolResult("Unable to read logs: ${it.message}", isError = true) }
    }

    private data class LaunchableApp(val label: String, val packageName: String)

    private fun launchableApps(): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun listApps(arguments: JsonObject): ToolResult {
        val query = arguments.string("query")
        val apps = launchableApps().filter {
            query == null || it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        if (apps.isEmpty()) return ToolResult("No launchable apps found.")
        return ToolResult(
            "${apps.size} app(s):\n" + apps.take(MAX_LISTED_APPS).joinToString("\n") { "${it.label} — ${it.packageName}" } +
                if (apps.size > MAX_LISTED_APPS) "\n... use query to narrow down" else ""
        )
    }

    private suspend fun launchApp(arguments: JsonObject): ToolResult {
        val requestedPackage = arguments.string("package_name")
        val appName = arguments.string("app_name")
        val packageName = when {
            requestedPackage != null -> requestedPackage
            appName != null -> {
                val apps = launchableApps()
                val exact = apps.filter { it.label.equals(appName, ignoreCase = true) }
                val matches = exact.ifEmpty { apps.filter { it.label.contains(appName, ignoreCase = true) } }
                when {
                    matches.isEmpty() -> return ToolResult(
                        "No launchable app named '$appName'. Use list_apps to see installed apps.",
                        isError = true
                    )
                    matches.size > 1 -> return ToolResult(
                        "App name '$appName' is ambiguous; use package_name. Candidates:\n" +
                            matches.take(MAX_LISTED_APPS).joinToString("\n") { "${it.label} — ${it.packageName}" },
                        isError = true
                    )
                    else -> matches.single().packageName
                }
            }
            else -> return ToolResult("Provide package_name or app_name.", isError = true)
        }
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
        val settleMs = (arguments.int("settle_ms") ?: DEFAULT_SETTLE_MS).coerceIn(0, MAX_SETTLE_MS).toLong()
        var settled = true
        if (settleMs > 0) {
            settled = waitForCondition(
                service, "idle", NodeSelector(), "", settleMs, SETTLE_POLL_MS, SETTLE_QUIET_MS
            ).passed
        }
        val foreground = service.rootInActiveWindow?.packageName
        return captureScreenshot(service, maxDimension).fold(
            onSuccess = { shot ->
                ToolResult(
                    text = "Captured at ${java.time.Instant.now()}" +
                        (foreground?.let { " (foreground: $it)" } ?: "") +
                        (if (settled) "" else "; UI was still changing") +
                        ". Screenshot ${shot.width}x${shot.height} (screen " +
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
        val explicitNth = arguments.int("nth")
        val node = if (selector.index != null) {
            ScreenNavigator.find(service, selector)
        } else {
            val matches = ScreenNavigator.findAll(service, selector.copy(nth = 0), MAX_QA_MATCHES)
            if (matches.size > 1 && explicitNth == null) {
                return ToolResult(
                    "${matches.size} elements match $selector; nothing was clicked. Pass nth (0-based) " +
                        "or a more specific selector. Candidates:\n" +
                        matches.take(MAX_CANDIDATES).withIndex().joinToString("\n") { (i, n) ->
                            val r = ScreenNavigator.bounds(n)
                            "nth=$i ${describeNode(n)} bounds=[${r.left},${r.top}][${r.right},${r.bottom}]" +
                                " clickable=${n.isClickable}"
                        },
                    isError = true
                )
            }
            matches.getOrNull(explicitNth ?: 0)
        } ?: return ToolResult(
            "No visible element matches $selector${explicitNth?.let { " (nth=$it)" }.orEmpty()}. Call get_screen_tree to refresh.",
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

    private suspend fun globalAction(arguments: JsonObject): ToolResult {
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
        val count = (arguments.int("count") ?: 1).coerceIn(1, MAX_REPEAT)
        val interval = (arguments.int("interval_ms") ?: DEFAULT_REPEAT_INTERVAL_MS).coerceIn(0, MAX_REPEAT_INTERVAL_MS).toLong()
        var done = 0
        repeat(count) { i ->
            if (!service.performGlobalAction(action)) {
                return ToolResult(
                    "The system rejected global action '$name' on attempt ${i + 1}/$count ($done done).",
                    isError = true
                )
            }
            done++
            if (i < count - 1) delay(interval)
        }
        return ToolResult("Performed global action '$name' x$done.")
    }


    private fun qaSession(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase()
            ?: return ToolResult("Missing required argument: action", isError = true)
        return when (action) {
            "begin" -> {
                val testCase = arguments.string("test_case")
                    ?: return ToolResult("Missing required argument: test_case", isError = true)
                val platform = arguments.string("platform") ?: "android"
                val device = deviceState.snapshot()
                qa.begin(testCase, platform, device, arguments.string("notes").orEmpty())
            }
            "step" -> qa.step(
                title = arguments.string("title").orEmpty(),
                expected = arguments.string("expected").orEmpty(),
                actual = arguments.string("actual").orEmpty(),
                status = arguments.string("status").orEmpty(),
                notes = arguments.string("notes").orEmpty()
            )
            "status" -> qa.status()
            "end" -> {
                val restore = deviceState.restore()
                val notes = listOfNotNull(
                    arguments.string("notes"),
                    "Device restore: $restore"
                ).joinToString("\n")
                val report = qa.end(arguments.string("result").orEmpty(), notes)
                if (report.isError) report else ToolResult("${report.text}\n$restore")
            }
            else -> ToolResult("action must be begin, step, status or end.", isError = true)
        }
    }

    private suspend fun uiFind(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val selector = qaSelector(arguments)
        if (selector.isEmpty) {
            return ToolResult(
                "Provide at least one selector: text, resource_id, content_desc, class_name or index.",
                isError = true
            )
        }
        val limit = (arguments.int("limit") ?: DEFAULT_QA_MATCHES).coerceIn(1, MAX_QA_MATCHES)
        val matches = ScreenNavigator.findAll(service, selector, limit)
        val payload = buildJsonObject {
            put("count", matches.size)
            put("selector", selector.toString())
            putJsonArray("matches") {
                matches.forEachIndexed { index, node -> add(nodeJson(index, node)) }
            }
        }
        return ToolResult(payload.toString())
    }

    private suspend fun uiWait(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val condition = arguments.string("condition")?.lowercase()
            ?: return ToolResult("Missing required argument: condition", isError = true)
        if (condition !in setOf("visible", "absent", "text", "idle")) {
            return ToolResult("condition must be visible, absent, text or idle.", isError = true)
        }
        val selector = qaSelector(arguments)
        if (condition != "idle" && selector.isEmpty) {
            return ToolResult("Provide a selector for condition '$condition'.", isError = true)
        }
        val timeout = (arguments.int("timeout_ms") ?: DEFAULT_QA_TIMEOUT_MS)
            .coerceIn(MIN_QA_TIMEOUT_MS, MAX_QA_TIMEOUT_MS)
        val interval = (arguments.int("interval_ms") ?: DEFAULT_QA_INTERVAL_MS)
            .coerceIn(MIN_QA_INTERVAL_MS, 2_000)
        val quiet = (arguments.int("quiet_ms") ?: DEFAULT_QA_QUIET_MS)
            .coerceIn(200, 5_000)
        val result = waitForCondition(
            service = service,
            condition = condition,
            selector = selector,
            expected = arguments.string("expected").orEmpty(),
            timeoutMs = timeout.toLong(),
            intervalMs = interval.toLong(),
            quietMs = quiet.toLong()
        )
        val detail = "condition=$condition elapsed=${result.elapsedMs}ms actual=${result.actual}"
        return if (result.passed) {
            ToolResult("WAIT OK: $detail")
        } else {
            ToolResult("WAIT TIMEOUT: $detail", isError = true)
        }
    }

    private suspend fun uiAssert(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val condition = arguments.string("condition")?.lowercase()
            ?: return ToolResult("Missing required argument: condition", isError = true)
        if (condition !in setOf("visible", "absent", "text", "count")) {
            return ToolResult("condition must be visible, absent, text or count.", isError = true)
        }
        var selector = qaSelector(arguments)
        val expected = arguments.string("expected").orEmpty()
        if (condition == "text" && selector.isEmpty && expected.isNotBlank()) {
            selector = selector.copy(text = expected)
        }
        if (selector.isEmpty) {
            return ToolResult("Provide a selector for condition '$condition'.", isError = true)
        }
        if (condition == "count" && expected.toIntOrNull() == null) {
            return ToolResult("condition=count requires an integer expected.", isError = true)
        }
        if (condition == "text" && expected.isBlank() && selector.text == null) {
            return ToolResult("condition=text requires expected or a text selector.", isError = true)
        }
        val timeout = (arguments.int("timeout_ms") ?: DEFAULT_QA_TIMEOUT_MS)
            .coerceIn(MIN_QA_TIMEOUT_MS, MAX_QA_TIMEOUT_MS)
        val result = waitForCondition(
            service = service,
            condition = condition,
            selector = selector,
            expected = expected,
            timeoutMs = timeout.toLong(),
            intervalMs = DEFAULT_QA_INTERVAL_MS.toLong(),
            quietMs = DEFAULT_QA_QUIET_MS.toLong()
        )
        if (result.passed) {
            return ToolResult(
                "ASSERTION PASSED: condition=$condition expected=${expected.ifBlank { selector.toString() }} " +
                    "actual=${result.actual} elapsed=${result.elapsedMs}ms"
            )
        }

        val maxDimension = (arguments.int("max_dimension") ?: DEFAULT_ASSERT_SCREENSHOT_DIMENSION)
            .coerceIn(MIN_SCREENSHOT_DIMENSION, MAX_SCREENSHOT_DIMENSION)
        val shot = captureScreenshot(service, maxDimension).getOrNull()
        val tree = ScreenNavigator.dumpTree(service, ASSERT_TREE_NODES)?.take(ASSERT_TREE_CHARS)
        val text = buildString {
            appendLine("ASSERTION FAILED: condition=$condition expected=${expected.ifBlank { selector.toString() }}")
            appendLine("actual=${result.actual} elapsed=${result.elapsedMs}ms")
            if (tree != null) {
                appendLine("--- tree excerpt ---")
                append(tree)
            }
        }.trimEnd()
        return ToolResult(
            text = text,
            isError = true,
            imageBase64 = shot?.let { java.util.Base64.getEncoder().encodeToString(it.jpegBytes) },
            imageMimeType = if (shot != null) "image/jpeg" else null
        )
    }

    private suspend fun uiGesture(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val type = arguments.string("type")?.lowercase()
            ?: return ToolResult("Missing required argument: type", isError = true)
        val selector = qaSelector(arguments)
        val node = if (!selector.isEmpty) ScreenNavigator.find(service, selector) else null
        val rect = node?.let { ScreenNavigator.bounds(it) }
        var x = arguments.float("x") ?: rect?.exactCenterX()
        var y = arguments.float("y") ?: rect?.exactCenterY()
        if (x == null || y == null) {
            return ToolResult(
                "Provide x and y coordinates, or a selector that matches a visible element.",
                isError = true
            )
        }
        val metrics = context.resources.displayMetrics
        if (x < 0f || y < 0f || x >= metrics.widthPixels || y >= metrics.heightPixels) {
            return ToolResult(
                "Coordinates are outside the screen (${metrics.widthPixels}x${metrics.heightPixels}).",
                isError = true
            )
        }
        val duration = (arguments.int("duration_ms") ?: if (type == "tap") 50 else 350)
            .coerceIn(20, 5_000)
            .toLong()
        val dispatched = when (type) {
            "tap" -> service.tap(x, y, duration)
            "double_tap" -> service.multiTap(x, y, count = 2, intervalMs = 120L, durationMs = duration)
            "multi_tap" -> {
                val count = (arguments.int("count") ?: 3).coerceIn(2, 10)
                val interval = (arguments.int("interval_ms") ?: 120).coerceIn(30, 1_000)
                service.multiTap(x, y, count, interval.toLong(), duration)
            }
            "long_press" -> service.tap(x, y, maxOf(duration, 800L))
            "swipe", "drag" -> {
                val toX = arguments.float("to_x")
                val toY = arguments.float("to_y")
                if (toX == null || toY == null) {
                    return ToolResult("$type requires to_x and to_y.", isError = true)
                }
                if (toX < 0f || toY < 0f || toX >= metrics.widthPixels || toY >= metrics.heightPixels) {
                    return ToolResult("Destination coordinates are outside the screen.", isError = true)
                }
                service.swipe(x, y, toX, toY, duration)
            }
            "pinch" -> {
                val radius = (arguments.float("radius") ?: 220f).coerceIn(40f, 600f)
                val direction = arguments.string("direction")?.lowercase() ?: "out"
                val start = if (direction == "in") radius else 80f
                val end = if (direction == "in") 80f else radius
                service.pinch(x, y, start, end, duration)
            }
            else -> return ToolResult(
                "type must be tap, double_tap, multi_tap, long_press, swipe, drag or pinch.",
                isError = true
            )
        }
        return if (dispatched) {
            ToolResult(
                "Gesture '$type' dispatched at (${x.toInt()}, ${y.toInt()})" +
                    if (type == "swipe" || type == "drag") {
                        " to (${arguments.float("to_x")?.toInt()}, ${arguments.float("to_y")?.toInt()})"
                    } else {
                        ""
                    } + "."
            )
        } else {
            ToolResult("The system rejected the gesture.", isError = true)
        }
    }

    private suspend fun audioInstruction(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase()
            ?: return ToolResult("Missing required argument: action", isError = true)
        return when (action) {
            "speak" -> audio.speak(
                text = arguments.string("text").orEmpty(),
                language = arguments.string("language") ?: "en-US",
                rate = arguments.float("rate") ?: 1.0f,
                volumePercent = arguments.int("volume") ?: 100,
                wait = arguments.bool("wait")
            )
            "play" -> audio.play(
                path = arguments.string("path").orEmpty(),
                volumePercent = arguments.int("volume") ?: 100,
                loop = arguments.bool("loop")
            )
            "stop" -> audio.stop()
            "set_volume" -> {
                val volume = arguments.int("volume")
                    ?: return ToolResult("set_volume requires volume (0-100).", isError = true)
                audio.setVolume(volume)
            }
            else -> ToolResult("action must be speak, play, stop or set_volume.", isError = true)
        }
    }

    private fun setDeviceState(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase()
            ?: return ToolResult("Missing required argument: action", isError = true)
        if (action == "restore") return ToolResult(deviceState.restore())
        if (action != "set") {
            return ToolResult("action must be set or restore.", isError = true)
        }
        if (!deviceState.hasSnapshot()) deviceState.snapshot()
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()

        arguments.string("rotation")?.let { value ->
            runCatching { deviceState.setRotation(value) }
                .onSuccess { applied += it }
                .onFailure { failed += "rotation: ${it.message}" }
        }
        arguments.string("dark_mode")?.let { value ->
            runCatching { deviceState.setDarkMode(value) }
                .onSuccess { applied += it }
                .onFailure { failed += "dark_mode: ${it.message}" }
        }
        arguments.float("font_scale")?.let { value ->
            runCatching { deviceState.setFontScale(value) }
                .onSuccess { applied += it }
                .onFailure { failed += "font_scale: ${it.message}" }
        }
        arguments.optionalBool("animations")?.let { value ->
            runCatching { deviceState.setAnimations(value) }
                .onSuccess { applied += it }
                .onFailure { failed += "animations: ${it.message}" }
        }
        arguments.int("volume")?.let { value ->
            runCatching { deviceState.setVolume(value) }
                .onSuccess { applied += it }
                .onFailure { failed += "volume: ${it.message}" }
        }
        arguments.optionalBool("keep_awake")?.let { value ->
            runCatching { deviceState.setKeepAwake(value) }
                .onSuccess { applied += it }
                .onFailure { failed += "keep_awake: ${it.message}" }
        }

        if (applied.isEmpty() && failed.isEmpty()) {
            return ToolResult(
                "Provide at least one field: rotation, dark_mode, font_scale, animations, volume or keep_awake.",
                isError = true
            )
        }
        val text = buildString {
            if (applied.isNotEmpty()) appendLine("Applied: ${applied.joinToString(" ")}")
            if (failed.isNotEmpty()) append("Failed: ${failed.joinToString("; ")}")
        }.trimEnd()
        return ToolResult(text, isError = failed.isNotEmpty())
    }

    private suspend fun uiDiff(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val action = arguments.string("action")?.lowercase()
            ?: return ToolResult("Missing required argument: action", isError = true)
        val rawName = arguments.string("name")
            ?: return ToolResult("Missing required argument: name", isError = true)
        if (!BASELINE_NAME_REGEX.matches(rawName)) {
            return ToolResult("name must be 1-64 chars: letters, digits, _ or -.", isError = true)
        }
        val maxDimension = (arguments.int("max_dimension") ?: DEFAULT_SCREENSHOT_DIMENSION)
            .coerceIn(MIN_SCREENSHOT_DIMENSION, MAX_SCREENSHOT_DIMENSION)
        val directory = File(context.filesDir, "qa/baselines").apply { mkdirs() }
        val baselineFile = File(directory, "$rawName.jpg")

        return when (action) {
            "save_baseline" -> {
                val shot = captureScreenshot(service, maxDimension).getOrElse {
                    return ToolResult("Screenshot failed: ${it.message}", isError = true)
                }
                runCatching { baselineFile.writeBytes(shot.jpegBytes) }.getOrElse {
                    return ToolResult("Unable to save baseline: ${it.message}", isError = true)
                }
                ToolResult(
                    "Baseline '$rawName' saved (${shot.width}x${shot.height}, ${shot.jpegBytes.size} bytes): " +
                        baselineFile.path
                )
            }
            "compare" -> {
                if (!baselineFile.isFile) {
                    return ToolResult(
                        "Baseline '$rawName' not found. Call ui_diff with action=save_baseline first.",
                        isError = true
                    )
                }
                val baseline = BitmapFactory.decodeFile(baselineFile.path)
                    ?: return ToolResult("Unable to decode baseline '$rawName'.", isError = true)
                val shot = captureScreenshot(service, maxDimension).getOrElse {
                    baseline.recycle()
                    return ToolResult("Screenshot failed: ${it.message}", isError = true)
                }
                val current = BitmapFactory.decodeByteArray(shot.jpegBytes, 0, shot.jpegBytes.size)
                    ?: run {
                        baseline.recycle()
                        return ToolResult("Unable to decode the current screenshot.", isError = true)
                    }
                val difference = runCatching { compareBitmaps(baseline, current) }.getOrElse {
                    baseline.recycle()
                    current.recycle()
                    return ToolResult("Diff failed: ${it.message}", isError = true)
                }
                baseline.recycle()
                current.recycle()
                val threshold = (arguments.float("threshold") ?: 1.0f).coerceIn(0f, 100f)
                val passed = difference <= threshold
                val text = "Visual diff '$rawName': ${"%.2f".format(difference)}% " +
                    "(threshold ${"%.2f".format(threshold)}%) -> ${if (passed) "PASS" else "FAIL"}. " +
                    "Baseline: ${baselineFile.path}"
                ToolResult(
                    text = text,
                    isError = !passed,
                    imageBase64 = if (passed) null else {
                        java.util.Base64.getEncoder().encodeToString(shot.jpegBytes)
                    },
                    imageMimeType = if (passed) null else "image/jpeg"
                )
            }
            else -> ToolResult("action must be save_baseline or compare.", isError = true)
        }
    }

    private suspend fun captureScreenshot(
        service: McpTouchAccessibilityService,
        maxDimension: Int
    ): Result<Screenshot> {
        var last: Result<Screenshot>? = null
        repeat(3) {
            val result = ScreenNavigator.takeScreenshot(service, maxDimension)
            if (result.isSuccess) return result
            last = result
            val message = result.exceptionOrNull()?.message.orEmpty()
            if (!message.contains("too quickly", ignoreCase = true)) return result
            delay(1_000L)
        }
        return last ?: Result.failure(IllegalStateException("Screenshot failed"))
    }

    private data class WaitResult(
        val passed: Boolean,
        val actual: String,
        val elapsedMs: Long
    )

    private suspend fun waitForCondition(
        service: McpTouchAccessibilityService,
        condition: String,
        selector: NodeSelector,
        expected: String,
        timeoutMs: Long,
        intervalMs: Long,
        quietMs: Long
    ): WaitResult {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + timeoutMs
        var actual = "not evaluated"
        var lastHash = 0
        var changedAt = started
        var initialized = false

        while (SystemClock.elapsedRealtime() <= deadline) {
            if (condition == "idle") {
                val tree = ScreenNavigator.dumpTree(service, MAX_TREE_NODES) ?: ""
                val hash = tree.hashCode()
                val now = SystemClock.elapsedRealtime()
                if (!initialized || hash != lastHash) {
                    initialized = true
                    lastHash = hash
                    changedAt = now
                }
                val stableFor = now - changedAt
                actual = "stable ${stableFor}ms"
                if (stableFor >= quietMs) {
                    return WaitResult(true, actual, now - started)
                }
            } else {
                val matches = ScreenNavigator.findAll(service, selector, MAX_QA_MATCHES)
                actual = when (condition) {
                    "visible" -> "matches=${matches.size}"
                    "absent" -> "matches=${matches.size}"
                    "text" -> "matches=${matches.size}"
                    "count" -> "count=${matches.size}"
                    else -> "matches=${matches.size}"
                }
                val passed = when (condition) {
                    "visible" -> matches.isNotEmpty()
                    "absent" -> matches.isEmpty()
                    "text" -> matches.isNotEmpty()
                    "count" -> expected.toIntOrNull() == matches.size
                    else -> false
                }
                if (passed) {
                    return WaitResult(true, actual, SystemClock.elapsedRealtime() - started)
                }
            }
            delay(intervalMs)
        }
        return WaitResult(false, actual, SystemClock.elapsedRealtime() - started)
    }

    private fun compareBitmaps(baseline: Bitmap, current: Bitmap): Float {
        val width = min(baseline.width, current.width)
        val height = min(baseline.height, current.height)
        if (width <= 0 || height <= 0) return 100f
        val scaledCurrent = if (baseline.width == current.width && baseline.height == current.height) {
            current
        } else {
            Bitmap.createScaledBitmap(current, width, height, true)
        }
        val baselinePixels = IntArray(width * height)
        val currentPixels = IntArray(width * height)
        baseline.getPixels(baselinePixels, 0, width, 0, 0, width, height)
        scaledCurrent.getPixels(currentPixels, 0, width, 0, 0, width, height)
        var different = 0L
        var total = 0L
        for (index in baselinePixels.indices) {
            val a = baselinePixels[index]
            val b = currentPixels[index]
            val dr = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
            val dg = abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
            val db = abs((a and 0xFF) - (b and 0xFF))
            if (dr + dg + db > DIFF_COLOR_DELTA) different++
            total++
        }
        if (scaledCurrent !== current) scaledCurrent.recycle()
        return if (total == 0L) 0f else different * 100f / total
    }

    private fun nodeJson(index: Int, node: android.view.accessibility.AccessibilityNodeInfo): JsonObject {
        val rect = ScreenNavigator.bounds(node)
        return buildJsonObject {
            put("match_index", index)
            if (node.isPassword) {
                put("text", "<password>")
            } else {
                node.text?.takeIf { it.isNotBlank() }?.let { put("text", it.toString().take(200)) }
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                put("content_desc", it.toString().take(200))
            }
            node.viewIdResourceName?.let { put("resource_id", it) }
            node.className?.toString()?.let { put("class", it) }
            put("bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
            put("clickable", node.isClickable)
            put("enabled", node.isEnabled)
            put("checked", node.isChecked)
            put("selected", node.isSelected)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
        }
    }

    private fun qaSelector(arguments: JsonObject): NodeSelector = NodeSelector(
        index = arguments.int("index"),
        text = arguments.string("text"),
        resourceId = arguments.string("resource_id"),
        contentDescription = arguments.string("content_desc"),
        textExact = arguments.bool("text_exact"),
        className = arguments.string("class_name"),
        nth = arguments.int("nth") ?: 0,
        includeInvisible = arguments.bool("include_invisible")
    )

    private fun JsonObject.optionalBool(key: String): Boolean? {
        val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return null
        return when (value) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toFloatOrNull()


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
        const val DEFAULT_QA_MATCHES = 20
        const val MAX_QA_MATCHES = 100
        const val MAX_CANDIDATES = 15
        const val MAX_REPEAT = 50
        const val DEFAULT_REPEAT_INTERVAL_MS = 300
        const val MAX_REPEAT_INTERVAL_MS = 5_000
        const val MAX_LOG_CHARS = 60_000
        const val MAX_LISTED_APPS = 200
        const val DEFAULT_SETTLE_MS = 1_500
        const val MAX_SETTLE_MS = 5_000
        const val SETTLE_POLL_MS = 150L
        const val SETTLE_QUIET_MS = 400L
        const val DEFAULT_QA_TIMEOUT_MS = 5_000
        const val MIN_QA_TIMEOUT_MS = 250
        const val MAX_QA_TIMEOUT_MS = 30_000
        const val DEFAULT_QA_INTERVAL_MS = 250
        const val MIN_QA_INTERVAL_MS = 100
        const val DEFAULT_QA_QUIET_MS = 700
        const val DEFAULT_ASSERT_SCREENSHOT_DIMENSION = 900
        const val ASSERT_TREE_NODES = 80
        const val ASSERT_TREE_CHARS = 4_000
        const val DIFF_COLOR_DELTA = 24
        val BASELINE_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
        val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
