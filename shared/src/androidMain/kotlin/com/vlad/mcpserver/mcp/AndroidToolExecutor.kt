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
import android.view.accessibility.AccessibilityNodeInfo
import com.vlad.mcpserver.McpTouchAccessibilityService
import com.vlad.mcpserver.NodeSelector
import com.vlad.mcpserver.ScreenNavigator
import com.vlad.mcpserver.Screenshot
import com.vlad.mcpserver.AppVisibility
import com.vlad.mcpserver.CameraCapture
import com.vlad.mcpserver.getLocalIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
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
    private val durableDelegate = lazy { DurableRoutes(context, routeExecutionMutex) }
    private val durable by durableDelegate
    private val startedAt = SystemClock.elapsedRealtime()
    private data class ReferencedNode(
        val node: AccessibilityNodeInfo, val path: List<Int>, val fingerprint: String
    )
    private val references = ElementReferences<ReferencedNode>(SystemClock::elapsedRealtime, newId = {
        UUID.randomUUID().toString()
    })

    override suspend fun execute(
        clientId: String,
        toolName: String,
        arguments: JsonObject
    ): ToolResult = withContext(Dispatchers.IO) {
            try {
                validateTargeting(toolName, arguments)
            } catch (error: IllegalArgumentException) {
                return@withContext targetingError("invalid_selector", error.message ?: "Invalid selector.")
            }
            val route = if (toolName == "run_route") {
                try {
                    val source = arguments["yaml"] as? JsonPrimitive
                    require(source?.isString == true) { "yaml must be a string." }
                    val parameters = arguments["parameters"]
                    require(parameters == null || parameters is JsonObject) { "parameters must be an object." }
                    YamlRouteParser.parse(source.content, parameters as? JsonObject ?: JsonObject(emptyMap()))
                } catch (error: IllegalArgumentException) {
                    return@withContext ToolResult("Invalid route: ${error.message}", isError = true)
                }
            } else null
            val approvalArguments = if (route == null) JsonObject(arguments.filterKeys { it != "run_key" }) else buildJsonObject {
                put("route_id", route.id)
                put("app_id", route.packageName)
                put("timeout_ms", route.timeoutMs)
                put("mode", arguments.string("mode") ?: "sync")
                put("disconnect_policy", "continue_until_terminal")
                put("failure_evidence", if (arguments.optionalBool("privacy_sensitive") == false)
                    "automatic bundle metadata only; direct assertion failure images may be unmasked"
                    else "bounded metadata; all free text/log messages redacted; screenshots omitted")
                put("privacy_sensitive", arguments.optionalBool("privacy_sensitive") ?: true)
                putJsonArray("steps") {
                    route.steps.forEach { step ->
                        add(buildJsonObject {
                            put("id", step.id)
                            put("tool", step.tool)
                            put("arguments", step.arguments)
                        })
                    }
                }
            }
            if (route != null) {
                return@withContext try {
                    durable.submit(route, arguments,
                        approve = { consent(clientId, toolName, approvalArguments) },
                        failureEvidence = { runId ->
                            collectFailureBundle(clientId, runId, route.packageName, includeScreenshot = false)
                        }
                    ) { step -> executeRouteStep(clientId, route, step,
                        privacySensitive = arguments.optionalBool("privacy_sensitive") ?: true) }
                } catch (error: IllegalArgumentException) {
                    targetingError("invalid_run", error.message ?: "Invalid run submission.")
                } catch (error: IllegalStateException) {
                    targetingError("run_unavailable", error.message ?: "Run store unavailable.")
                }
            }
            if (McpTools.isSensitive(toolName) && !consent(clientId, toolName, approvalArguments)) {
                return@withContext ToolResult("User rejected the tool call.", isError = true,
                    structuredContent = buildJsonObject { put("reason", "consent_denied"); put("retryable", false) })
            }
            if (toolName in ROUTE_SERIALIZED_TOOLS) {
                if (toolName in setOf("wait_for_state", "collect_failure_bundle")) {
                    var acquired = false
                    try {
                        val ready = withTimeoutOrNull(10_000) {
                            routeExecutionMutex.lock()
                            acquired = true
                            true
                        } == true
                        if (!ready) return@withContext targetingError("queue_timeout", "Device busy; nothing was executed.")
                        return@withContext executeTool(clientId, toolName, arguments)
                    } finally { if (acquired) routeExecutionMutex.unlock() }
                }
                routeExecutionMutex.withLock { executeTool(clientId, toolName, arguments) }
            } else executeTool(clientId, toolName, arguments)
        }

    private suspend fun executeRouteStep(
        clientId: String, route: YamlRoute, step: RouteStep, privacySensitive: Boolean
    ): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val waitsForApp = step.tool == "ui_wait" && step.arguments.string("condition") == "idle"
        if (step.tool != "launch_app" && !waitsForApp &&
            service.rootInActiveWindow?.packageName?.toString() != route.packageName
        ) {
            return ToolResult("Route stopped: target app is not in the foreground.", isError = true)
        }
        if (step.tool in setOf("click_element", "input_text", "scroll")) {
            val selector = if (step.tool == "input_text") step.arguments.selector("match_text")
                else step.arguments.selector().copy(
                    className = step.arguments.string("class_name"),
                    textExact = step.arguments.bool("text_exact")
                )
            if (!selector.isEmpty && ScreenNavigator.findAll(service, selector, 2).size != 1) {
                return ToolResult("Route stopped: selector must match exactly one visible element.", isError = true)
            }
        }
        val result = if (step.tool == "ui_assert") uiAssert(step.arguments, includeImage = !privacySensitive)
            else executeTool(clientId, step.tool, step.arguments)
        if (!result.isError && step.tool in setOf("ui_wait", "ui_assert") &&
            service.rootInActiveWindow?.packageName?.toString() != route.packageName
        ) {
            return ToolResult("Route stopped: foreground app changed during the check.", isError = true)
        }
        return result
    }

    private suspend fun executeTool(clientId: String, toolName: String, arguments: JsonObject): ToolResult {
        val result = when (toolName) {
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
                "get_capabilities" -> capabilities()
                "get_screen_tree" -> screenTree(arguments)
                "observe_screen" -> observeScreen(clientId, arguments)
                "wait_for_state" -> waitForState(arguments)
                "get_run" -> durable.get(arguments)
                "cancel_run" -> durable.cancel(arguments)
                "get_build_identity" -> buildIdentity(arguments)
                "collect_failure_bundle" -> {
                    val run = durable.owned(arguments)
                    val runId = run.string("run_id")!!
                    val pkg = arguments.string("package_name")
                        ?: return targetingError("invalid_package", "package_name is required.")
                    if (run.string("target_package") != pkg) return targetingError(
                        "wrong_package", "Evidence must target the package approved for this run."
                    )
                    if (arguments.bool("include_screenshot") && run["privacy_sensitive"] != JsonPrimitive(false)) {
                        return targetingError("privacy_policy", "Privacy-sensitive runs cannot capture unmasked images.")
                    }
                    collectFailureBundle(clientId, runId, pkg, arguments.bool("include_screenshot"))
                }
                "export_run" -> exportRun(arguments)
                "get_artifact" -> readArtifact(arguments)
                "take_screenshot" -> screenshot(arguments)
                "click_element" -> clickElement(clientId, arguments)
                "input_text" -> inputText(clientId, arguments)
                "scroll" -> scroll(clientId, arguments)
                "global_action" -> globalAction(arguments)
                "qa_session" -> qaSession(arguments)
                "ui_find" -> uiFind(clientId, arguments)
                "ui_wait" -> uiWait(arguments)
                "ui_assert" -> uiAssert(arguments)
                "ui_gesture" -> uiGesture(clientId, arguments)
                "audio_instruction" -> audioInstruction(arguments)
                "audio_state" -> audio.state()
                "get_device_state" -> ToolResult(deviceState.readText())
                "device_state" -> setDeviceState(arguments)
                "ui_diff" -> uiDiff(arguments)
                else -> ToolResult("Tool '$toolName' is not available.", isError = true)
            }
        if (!result.isError && toolName in setOf("click_element", "input_text", "scroll", "ui_gesture",
                "touch_test", "launch_app", "swipe", "global_action", "device_state")) references.clear()
        return result
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

    private fun capabilities(): ToolResult {
        val touchTools = setOf(
            "touch_test", "swipe", "get_screen_tree", "take_screenshot", "click_element",
            "input_text", "scroll", "global_action", "ui_find", "ui_wait", "ui_assert",
            "ui_gesture", "ui_diff", "run_route", "observe_screen", "wait_for_state", "collect_failure_bundle"
        )
        val mutations = setOf(
            "touch_test", "launch_app", "swipe", "click_element", "input_text", "scroll",
            "global_action", "ui_gesture", "run_route", "create_notification", "take_photo",
            "qa_session", "ui_diff", "audio_instruction", "device_state", "collect_failure_bundle", "export_run", "cancel_run"
        )
        val result = buildJsonObject {
            put("platform", "android")
            put("server_version", McpDispatcher.SERVER_VERSION)
            put("api_level", Build.VERSION.SDK_INT)
            put("minimum_api", 35)
            putJsonObject("permissions") {
                put("accessibility_service", touchService() != null)
                put("camera", context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                put("notifications", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                put("read_logs", context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED)
                put("write_settings", Settings.System.canWrite(context))
                put("write_secure_settings", deviceState.canWriteSecureSettings())
                put("overlay", Settings.canDrawOverlays(context))
            }
            putJsonArray("tools") {
                McpTools.available().forEach { tool ->
                    val missing = buildList {
                        if (tool.name in touchTools && touchService() == null) add("accessibility_service")
                        if (tool.name == "take_photo" &&
                            context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add("camera")
                        if (tool.name == "create_notification" &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add("notifications")
                    }
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("execution_location", "android_device")
                        put("implemented", true)
                        put("status", if (missing.isNotEmpty()) "blocked" else
                            if (tool.name in touchTools || tool.name in setOf("device_state", "get_logs", "launch_app", "take_photo")) "conditional"
                            else "available")
                        putJsonArray("missing_permissions") { missing.forEach { add(it) } }
                        put("requires_confirmation_when_enabled", tool.sensitive)
                        put("target_app_dependency", when (tool.name) {
                            "get_logs" -> "Other-app logs require READ_LOGS and OS/user log-access approval."
                            "launch_app", "get_app_version", "get_build_identity" -> "Target must be installed and visible to Android package queries."
                            in touchTools -> "Target must expose an accessible window; secure screens block capture."
                            else -> "none"
                        })
                        put("mutation", tool.name in mutations)
                        put("reversibility", when (tool.name) {
                            "device_state" -> "snapshot_restore; partial failures possible"
                            "audio_instruction" -> "manual_restore"
                            in mutations -> "not_guaranteed"
                            else -> "not_applicable"
                        })
                        put("potentially_destructive", tool.name in setOf(
                            "click_element", "input_text", "ui_gesture", "touch_test", "global_action", "run_route"
                        ))
                        tool.requirement?.let { put("requirement", it) }
                    })
                }
            }
            putJsonObject("device_state_requirements") {
                put("rotation_font_scale", "WRITE_SETTINGS")
                put("dark_mode_animations", "WRITE_SECURE_SETTINGS; effective dark-mode application is OS-dependent")
                put("volume_keep_awake", "System policy may reject changes; restore requires a snapshot")
            }
            putJsonArray("unsupported_controls") {
                listOf("permission_grant_revoke", "global_locale", "mock_location", "clipboard",
                    "other_app_data_reset", "device_time", "connectivity").forEach { add(it) }
            }
        }
        return ToolResult(result.toString(), structuredContent = result)
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
                "Notification permission is not granted. Enable notifications for Tester in Android settings.",
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
                "Camera capture is only allowed while the Tester app is in the foreground. " +
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
                "\ntouch_service=${McpTouchAccessibilityService.instance() != null}" +
                "\nwrite_settings=${Settings.System.canWrite(context)}" +
                "\nwrite_secure_settings=${deviceState.canWriteSecureSettings()}" +
                "\nread_logs=${context.checkSelfPermission(android.Manifest.permission.READ_LOGS) == android.content.pm.PackageManager.PERMISSION_GRANTED}" +
                "\noverlay=${Settings.canDrawOverlays(context)}"
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
        val buffer = if (arguments.bool("crashes")) "crash" else "main"
        val pids = pkg?.let { processIds(it) }.orEmpty()
        if (pkg != null && pids.isEmpty()) {
            return ToolResult(
                "No current or past process found for '$pkg' in the log buffers. Past processes are found " +
                    "through system/crash logs, which need READ_LOGS (adb shell pm grant ${context.packageName} " +
                    "android.permission.READ_LOGS) and, on Android 13+, tapping 'Allow' on the system " +
                    "'access all device logs' dialog (shown once per Tester process; click_element can tap it). Retry after allowing.",
                isError = true
            )
        }
        return runCatching {
            val output = runLogcat(listOf("-b", buffer, "*:$level"))
            val filtered = output
                .filter { pids.isEmpty() || logPid(it) in pids }
                .filter { query == null || it.contains(query, ignoreCase = true) }
                .takeLast(lines)
            val header = if (pkg != null) "pids for $pkg: ${pids.joinToString(", ")}\n" else ""
            if (filtered.isEmpty()) {
                ToolResult(
                    header + "No log lines matched. Other apps' logs require READ_LOGS (adb shell pm grant " +
                        "${context.packageName} android.permission.READ_LOGS) and, on Android 13+, allowing the system log-access dialog."
                )
            } else {
                ToolResult((header + filtered.joinToString("\n")).take(MAX_LOG_CHARS))
            }
        }.getOrElse { ToolResult("Unable to read logs: ${it.message}", isError = true) }
    }

    private fun runLogcat(args: List<String>): List<String> {
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime") + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        return output
    }

    private fun logPid(line: String): String? = LOG_PID_REGEX.find(line)?.groupValues?.get(1)

    /** Live PIDs plus PIDs of earlier (e.g. crashed) processes found in system/crash logs. */
    private fun processIds(pkg: String): Set<String> {
        val pids = linkedSetOf<String>()
        runCatching {
            ProcessBuilder("pidof", pkg).redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        }.getOrNull()?.let(pids::addAll)
        val quoted = Regex.escape(pkg)
        val patterns = listOf(
            Regex("Start proc (\\d+):$quoted[/:\\s]"),
            Regex("Process: $quoted, PID: (\\d+)"),
            Regex("Process $quoted \\(pid (\\d+)\\)"),
            Regex("Killing (\\d+):$quoted[/:\\s]")
        )
        runCatching { runLogcat(listOf("-b", "system,crash", "*:V")) }.getOrNull()?.forEach { line ->
            if (!line.contains(pkg)) return@forEach
            patterns.firstNotNullOfOrNull { it.find(line) }?.let { pids += it.groupValues[1] }
        }
        return pids
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
                "Cannot launch apps while Tester is in the background. Grant 'Display over " +
                    "other apps' (Tester Settings > Device access), enable 'MCP Touch Service' " +
                    "in Accessibility, or open Tester, then try again.",
                isError = true
            )
        }

        val failure = withContext(Dispatchers.Main) {
            runCatching { context.startActivity(intent) }.exceptionOrNull()
        }
        if (failure != null) {
            return ToolResult("Unable to launch $packageName: ${failure.message}", isError = true)
        }
        val waitMs = (arguments.int("wait_ms") ?: DEFAULT_LAUNCH_WAIT_MS).coerceIn(0, MAX_QA_TIMEOUT_MS).toLong()
        val service = touchService()
        if (waitMs == 0L || service == null) {
            return ToolResult("Launched $packageName (not waited; call ui_wait idle with package_name before inspecting).")
        }
        val wait = waitForCondition(
            service, "idle", NodeSelector(), "", waitMs, SETTLE_POLL_MS, DEFAULT_QA_QUIET_MS.toLong(), packageName
        )
        return if (wait.passed) {
            ToolResult("Launched $packageName; it is in the foreground and the UI settled after ${wait.elapsedMs}ms.")
        } else {
            ToolResult(
                "Launched $packageName, but after ${wait.elapsedMs}ms: ${wait.actual}. " +
                    "It may still be starting; call ui_wait idle with package_name=$packageName.",
                isError = true,
                structuredContent = wait.structured("idle", packageName)
            )
        }
    }

    private fun touchService(): McpTouchAccessibilityService? = McpTouchAccessibilityService.instance()

    private fun touchServiceMissing(): ToolResult = ToolResult(
        "This tool needs 'MCP Touch Service'. Enable it in Android Settings > Accessibility " +
            "(or Tester Settings > Device access), then try again.",
        isError = true,
        structuredContent = buildJsonObject {
            put("reason", "permission_denied")
            put("requirement", "accessibility_service")
            put("retryable", false)
        }
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"

    private fun JsonObject.selector(textKey: String = "text"): NodeSelector =
        qaSelector(this, textKey)

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
                service, "idle", NodeSelector(), "", settleMs, SETTLE_POLL_MS, SETTLE_QUIET_MS,
                arguments.string("package_name")
            ).passed
        }
        val foreground = service.rootInActiveWindow?.packageName
        return captureScreenshot(service, maxDimension).fold(
            onSuccess = { shot ->
                ToolResult(
                    text = "Captured at ${java.time.Instant.now()}" +
                        (foreground?.let { " (foreground: $it)" } ?: "") +
                        (if (settled) "" else "; UI was still changing or the expected app was not in front") +
                        (if (foreground == context.packageName) "; WARNING: Tester itself is in front" else "") +
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

    private suspend fun clickElement(clientId: String, arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val selector = arguments.selector().copy(
            textExact = arguments.bool("text_exact"),
            className = arguments.string("class_name")
        )
        if (selector.isEmpty && arguments.string("element_ref") == null) {
            return ToolResult("Provide index, text, resource_id, content_desc or class_name.", isError = true)
        }
        val target = resolveTarget(clientId, service, arguments, selector)
        val node = target.node ?: return target.error!!
        val longPress = arguments.bool("long_press")
        val description = describeNode(node)
        val method = ScreenNavigator.click(service, node, longPress, selector.within)
        return if (method != null) {
            ToolResult("${if (longPress) "Long-pressed" else "Clicked"} $description via $method.")
        } else {
            ToolResult("Unable to click $description: ${tapFailureReason(service)}", isError = true)
        }
    }

    private fun inputText(clientId: String, arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val text = (arguments["text"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult("Missing required argument: text", isError = true)
        if (text.length > MAX_INPUT_CHARS) {
            return ToolResult("Text is too long (max $MAX_INPUT_CHARS characters).", isError = true)
        }
        val selector = arguments.selector(textKey = "match_text")
        val explicit = !selector.isEmpty || arguments.string("element_ref") != null
        val target = if (explicit) resolveTarget(clientId, service, arguments, selector) else null
        if (target?.error != null) return target.error
        val fields = target?.node?.let(ScreenNavigator::editableTargets)
        if (fields != null && fields.size > 1) return targetingError(
            "ambiguous_match", "Container has multiple editable fields; nothing was entered."
        )
        val field = if (explicit) fields?.singleOrNull() else ScreenNavigator.editableTarget(service, null)
            ?: return ToolResult(
                if (selector.isEmpty) {
                    "No focused text field. Pass a selector or click the field first."
                } else {
                    "The matched element is not an editable field."
                },
                isError = true
            )
        if (field == null) return targetingError("no_match", "The matched element is not an editable field.")
        if (!field.isEnabled || !ScreenNavigator.visibleByPolicy(field,
                arguments.string("visibility") ?: "fully_visible", service)) {
            return targetingError("not_actionable", "The field is disabled or fails the visibility policy.")
        }
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

    private suspend fun scroll(clientId: String, arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val direction = arguments.string("direction")?.lowercase()
        if (direction !in McpTools.SCROLL_DIRECTIONS) {
            return ToolResult(
                "Argument direction must be one of ${McpTools.SCROLL_DIRECTIONS}.",
                isError = true
            )
        }
        val selector = arguments.selector()
        val node = if (selector.isEmpty && arguments.string("element_ref") == null) {
            ScreenNavigator.primaryScrollable(service)
        } else {
            val target = resolveTarget(clientId, service, arguments, selector)
            target.node ?: return target.error!!
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
                val restore = if (deviceState.hasSnapshot()) deviceState.restore() else ToolResult("No state changes to restore.")
                val notes = listOfNotNull(
                    arguments.string("notes"),
                    "Device restore: ${restore.text}"
                ).joinToString("\n")
                val report = qa.end(if (restore.isError) "FAIL" else arguments.string("result").orEmpty(), notes)
                ToolResult(
                    "${report.text}\n${restore.text}",
                    isError = report.isError || restore.isError,
                    structuredContent = restore.structuredContent
                )
            }
            else -> ToolResult("action must be begin, step, status or end.", isError = true)
        }
    }

    private suspend fun uiFind(clientId: String, arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val selector = qaSelector(arguments)
        if (selector.isEmpty) {
            return ToolResult(
                "Provide at least one selector: text, resource_id, content_desc, class_name or index.",
                isError = true
            )
        }
        val limit = (arguments.int("limit") ?: DEFAULT_QA_MATCHES).coerceIn(1, MAX_QA_MATCHES)
        val root = service.rootInActiveWindow ?: return targetingError("no_window", "No active window.")
        val started = System.currentTimeMillis()
        val window = observationWindow(service, root)
        val matches = ScreenNavigator.findAll(service, selector, limit, root)
        val snapshot = ScreenNavigator.snapshot(root, MAX_TREE_NODES)
        val paths = matches.map { node -> snapshot.allNodes.firstOrNull { it.node == node } }
        val recorded = references.record(clientId, window, paths.mapNotNull { item ->
            item?.let { ReferencedNode(it.node, it.path, fingerprint(it.node)) }
        })
        val stable = window == observationWindow(service, service.rootInActiveWindow)
        var referenceIndex = 0
        val payload = buildJsonObject {
            put("observation_id", recorded.first)
            put("capture_started_at_ms", started)
            put("capture_finished_at_ms", System.currentTimeMillis())
            put("foreground_package", window.packageName)
            put("window_id", window.windowId)
            put("window_generation", window.generation)
            put("reference_lifetime_ms", 30_000)
            put("unstable", !stable)
            put("truncated", matches.size >= limit || snapshot.truncated)
            put("count", matches.size)
            put("selector", selector.toString())
            putJsonArray("matches") {
                matches.forEachIndexed { index, node ->
                    val ref = if (paths[index] != null) recorded.second[referenceIndex++] else null
                    add(JsonObject(nodeJson(selector.nth.coerceAtLeast(0) + index, node, service) +
                        if (ref != null && stable) mapOf("element_ref" to JsonPrimitive(ref)) else emptyMap()))
                }
            }
        }
        return ToolResult(payload.toString(), structuredContent = payload)
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
        val expected = arguments.rawString("expected")
        val mode = arguments.string("text_mode") ?: "exact"
        if (mode !in setOf("exact", "contains") ||
            (condition == "text" && expected == null && selector.text == null)) {
            return ToolResult("Text checks need expected or a text selector; text_mode must be exact or contains.", isError = true)
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
            expected = expected,
            timeoutMs = timeout.toLong(),
            intervalMs = interval.toLong(),
            quietMs = quiet.toLong(),
            packageName = arguments.string("package_name"),
            contains = mode == "contains",
            ignoreCase = arguments.bool("ignore_case")
        )
        val detail = "condition=$condition elapsed=${result.elapsedMs}ms actual=${result.actual}"
        return if (result.passed) {
            ToolResult("WAIT OK: $detail", structuredContent = result.structured(condition, expected))
        } else {
            val label = if (result.outcome.reason in setOf("ambiguous_match", "truncated_count")) "WAIT FAILED" else "WAIT TIMEOUT"
            ToolResult("$label: $detail", isError = true, structuredContent = result.structured(condition, expected))
        }
    }

    private suspend fun uiAssert(arguments: JsonObject, includeImage: Boolean = true): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val condition = arguments.string("condition")?.lowercase()
            ?: return ToolResult("Missing required argument: condition", isError = true)
        if (condition !in setOf("visible", "absent", "text", "count")) {
            return ToolResult("condition must be visible, absent, text or count.", isError = true)
        }
        var selector = qaSelector(arguments)
        val expected = arguments.rawString("expected")
        if (condition == "text" && selector.isEmpty && !expected.isNullOrEmpty()) {
            selector = selector.copy(text = expected, textExact = true)
        }
        if (selector.isEmpty) {
            return ToolResult("Provide a selector for condition '$condition'.", isError = true)
        }
        if (condition == "count" && expected?.toIntOrNull()?.let { it >= 0 } != true) {
            return ToolResult("condition=count requires a nonnegative integer expected.", isError = true)
        }
        if (condition == "text" && expected == null && selector.text == null) {
            return ToolResult("condition=text requires expected or a text selector.", isError = true)
        }
        val mode = arguments.string("text_mode") ?: "exact"
        if (mode !in setOf("exact", "contains")) {
            return ToolResult("text_mode must be exact or contains.", isError = true)
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
            quietMs = DEFAULT_QA_QUIET_MS.toLong(),
            packageName = arguments.string("package_name"),
            contains = mode == "contains",
            ignoreCase = arguments.bool("ignore_case")
        )
        if (result.passed) {
            return ToolResult(
                "ASSERTION PASSED: condition=$condition expected=${expected ?: selector.toString()} " +
                    "actual=${result.actual} elapsed=${result.elapsedMs}ms",
                structuredContent = result.structured(condition, expected)
            )
        }

        val maxDimension = (arguments.int("max_dimension") ?: DEFAULT_ASSERT_SCREENSHOT_DIMENSION)
            .coerceIn(MIN_SCREENSHOT_DIMENSION, MAX_SCREENSHOT_DIMENSION)
        val shot = if (includeImage) captureScreenshot(service, maxDimension).getOrNull() else null
        val tree = ScreenNavigator.dumpTree(service, ASSERT_TREE_NODES)?.take(ASSERT_TREE_CHARS)
        val text = buildString {
            appendLine("ASSERTION FAILED: condition=$condition expected=${expected ?: selector.toString()}")
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
            imageMimeType = if (shot != null) "image/jpeg" else null,
            structuredContent = result.structured(condition, expected)
        )
    }

    private suspend fun uiGesture(clientId: String, arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val type = arguments.string("type")?.lowercase()
            ?: return ToolResult("Missing required argument: type", isError = true)
        val selector = qaSelector(arguments)
        val explicit = !selector.isEmpty || arguments.string("element_ref") != null
        if (explicit && (arguments["x"] != null || arguments["y"] != null)) {
            return targetingError("invalid_selector", "Use a selector/reference or coordinates, not both.")
        }
        val target = if (explicit) resolveTarget(clientId, service, arguments, selector) else null
        if (target?.error != null) return target.error
        val rect = target?.node?.let { ScreenNavigator.visibleBounds(it, service) }
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
        if (action == "restore") return deviceState.restore()
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
        return ToolResult(text, isError = failed.isNotEmpty(), structuredContent = buildJsonObject {
            put("reason", if (failed.isEmpty()) "applied" else "partial_set")
            put("snapshot_retained", deviceState.hasSnapshot())
            put("retryable", failed.isNotEmpty())
            putJsonArray("applied") { applied.forEach { add(it) } }
            putJsonArray("failed") { failed.forEach { add(it) } }
        })
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
        maxDimension: Int,
        windowId: Int? = null
    ): Result<Screenshot> {
        var last: Result<Screenshot>? = null
        repeat(3) {
            val result = ScreenNavigator.takeScreenshot(service, maxDimension, windowId)
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
        val elapsedMs: Long,
        val outcome: PredicateOutcome = PredicateOutcome(passed, if (passed) "matched" else "not_idle", emptyList())
    ) {
        fun structured(condition: String, expected: String?): JsonObject =
            outcome.structured(condition, expected, elapsedMs)
    }

    private fun JsonObject.rawString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun waitForCondition(
        service: McpTouchAccessibilityService,
        condition: String,
        selector: NodeSelector,
        expected: String?,
        timeoutMs: Long,
        intervalMs: Long,
        quietMs: Long,
        packageName: String? = null,
        contains: Boolean = false,
        ignoreCase: Boolean = false
    ): WaitResult {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + timeoutMs
        var actual = "not evaluated"
        var lastHash = 0
        var changedAt = started
        var initialized = false
        var targetPackage = packageName ?: service.rootInActiveWindow?.packageName?.toString()
        var outcome = PredicateOutcome(false, "no_window", emptyList())

        while (SystemClock.elapsedRealtime() <= deadline) {
            if (condition == "idle") {
                val foreground = service.rootInActiveWindow?.packageName?.toString()
                val tree = ScreenNavigator.dumpTree(service, MAX_TREE_NODES) ?: ""
                val now = SystemClock.elapsedRealtime()
                // A missing/empty window or the wrong foreground app is a transition, not idle.
                if (targetPackage == null && foreground != null) targetPackage = foreground
                val ready = tree.isNotBlank() && foreground != null && foreground == targetPackage
                val hash = if (ready) tree.hashCode() else 0
                if (!initialized || !ready || hash != lastHash) {
                    initialized = true
                    lastHash = hash
                    changedAt = now
                }
                val stableFor = now - changedAt
                actual = if (!ready) {
                    "waiting for ${targetPackage ?: "a window"} (foreground: ${foreground ?: "none"})"
                } else {
                    "stable ${stableFor}ms (foreground: $foreground)"
                }
                outcome = PredicateOutcome(false, when {
                    foreground == null || tree.isBlank() -> "no_window"
                    foreground != targetPackage -> "wrong_package"
                    else -> "not_idle"
                }, listOf(actual))
                if (ready && stableFor >= quietMs) {
                    return WaitResult(true, actual, now - started, PredicateOutcome(true, "matched", listOf(actual)))
                }
            } else {
                val root = service.rootInActiveWindow
                val foreground = root?.packageName?.toString()
                if (targetPackage == null && foreground != null) targetPackage = foreground
                val matches = ScreenNavigator.findAll(service, selector, MAX_QA_MATCHES, root)
                outcome = UiPredicate.evaluate(
                    condition, matches.map { if (it.isPassword) null else it.text?.toString() },
                    expected, root != null, foreground, targetPackage,
                    truncated = matches.size >= MAX_QA_MATCHES, contains = contains, ignoreCase = ignoreCase
                )
                actual = "${outcome.reason} matches=${matches.size}" +
                    if (condition == "text") " text=${outcome.actual}" else ""
                if (outcome.passed || outcome.reason in setOf("ambiguous_match", "truncated_count")) {
                    return WaitResult(outcome.passed, actual, SystemClock.elapsedRealtime() - started, outcome)
                }
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            delay(minOf(intervalMs, remaining))
        }
        return WaitResult(false, actual, SystemClock.elapsedRealtime() - started, outcome)
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

    private fun nodeJson(index: Int, node: AccessibilityNodeInfo, service: McpTouchAccessibilityService): JsonObject {
        val rect = ScreenNavigator.bounds(node)
        val visible = ScreenNavigator.visibleBounds(node, service)
        val inWindow = android.graphics.Rect().also(node::getBoundsInWindow)
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
            put("bounds_in_window", "[${inWindow.left},${inWindow.top}][${inWindow.right},${inWindow.bottom}]")
            putJsonObject("visible_bounds") {
                put("left", visible.left); put("top", visible.top)
                put("right", visible.right); put("bottom", visible.bottom)
            }
            put("accessibility_visible", node.isVisibleToUser)
            put("viewport_intersecting", !visible.isEmpty)
            put("fully_visible", ScreenNavigator.visibleByPolicy(node, "fully_visible", service))
            put("reported_clipping", ScreenNavigator.reportsClipping(node))
            put("visibility_basis", "reported_accessibility_geometry; original extent and overlays may be unknown")
            put("actionable", node.isEnabled && ScreenNavigator.visibleByPolicy(node, "fully_visible", service) &&
                (ScreenNavigator.hasClickAction(node) || node.isEditable || node.isScrollable))
            put("occlusion_known", false)
            put("clickable", node.isClickable)
            put("click_action", ScreenNavigator.hasClickAction(node))
            put("enabled", node.isEnabled)
            put("checked", node.isChecked)
            put("selected", node.isSelected)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
        }
    }

    private fun qaSelector(arguments: JsonObject, textKey: String = "text"): NodeSelector = NodeSelector(
        index = arguments.int("index"),
        text = arguments.string(textKey),
        resourceId = arguments.string("resource_id"),
        contentDescription = arguments.string("content_desc"),
        textExact = arguments.bool("text_exact"),
        className = arguments.string("class_name"),
        nth = arguments.int("nth") ?: 0,
        includeInvisible = arguments.bool("include_invisible"),
        within = (arguments["within"] as? JsonObject)?.let { qaSelector(it) },
        visibility = arguments.string("visibility") ?: "accessibility"
    )

    private fun validateTargeting(toolName: String, arguments: JsonObject) {
        if (toolName in setOf("run_route", "get_run", "cancel_run", "collect_failure_bundle",
                "export_run", "get_artifact", "get_build_identity", "wait_for_state")) {
            for (name in listOf("mode", "run_key", "submission_id", "disconnect_policy", "run_id",
                    "artifact_id", "package_name", "format")) arguments[name]?.let {
                require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "$name must be a nonempty string." }
            }
            for (name in listOf("privacy_sensitive", "include_screenshot")) arguments[name]?.let {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "$name must be a boolean." }
            }
            for (name in listOf("queue_timeout_ms", "timeout_ms", "interval_ms")) arguments[name]?.let {
                require(it is JsonPrimitive && !it.isString && it.longOrNull != null) { "$name must be an integer." }
            }
        }
        arguments["within"]?.let {
            require(it is JsonObject && it.isNotEmpty()) { "within must be a nonempty container selector." }
            require(it.keys.all { key -> key in setOf("text", "text_exact", "resource_id", "content_desc", "class_name") }) {
                "within accepts text, text_exact, resource_id, content_desc and class_name only."
            }
            require(it.any { (key, value) -> key != "text_exact" && value is JsonPrimitive &&
                value.isString && value.content.isNotBlank() }) { "within requires a container identity." }
            it.forEach { (key, value) ->
                require(value is JsonPrimitive && if (key == "text_exact") value.content in setOf("true", "false")
                    else value.isString && value.content.isNotBlank()) { "Invalid within field: $key." }
            }
        }
        arguments["visibility"]?.let {
            require((it as? JsonPrimitive)?.content in setOf("accessibility", "intersecting", "fully_visible")) {
                "visibility must be accessibility, intersecting or fully_visible."
            }
        }
        arguments["element_ref"]?.let {
            require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "element_ref must be a nonempty string." }
            require(arguments.keys.none { key -> key in setOf("index", "within", "resource_id", "match_text",
                "content_desc", "class_name", "nth", "text_exact") }) { "element_ref cannot be combined with selectors." }
            require(toolName == "input_text" || "text" !in arguments) { "element_ref cannot be combined with text selectors." }
        }
        if ("index" in arguments) require(arguments.keys.none { it in setOf("within", "resource_id",
                "content_desc", "class_name", "nth", "match_text") } &&
            (toolName == "input_text" || "text" !in arguments)) { "Legacy index cannot be combined with other selectors." }
        arguments["nth"]?.let {
            require((it as? JsonPrimitive)?.content?.toIntOrNull()?.let { n -> n in 0..99 } == true) {
                "nth must be in 0..99."
            }
        }
    }

    private fun targetingError(reason: String, message: String) = ToolResult(
        message, isError = true, structuredContent = buildJsonObject {
            put("reason", reason); put("action_dispatched", false); put("retryable", false)
        }
    )

    private fun observationWindow(service: McpTouchAccessibilityService, root: AccessibilityNodeInfo?): ObservationWindow {
        val metrics = service.resources.displayMetrics
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        return ObservationWindow(service.windowGeneration.get(), root?.windowId ?: -1,
            root?.packageName?.toString().orEmpty(), display?.rotation ?: -1, metrics.widthPixels, metrics.heightPixels)
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String = listOf(
        node.windowId, node.packageName, node.className, node.viewIdResourceName,
        if (node.isPassword) "<password>" else node.text, node.contentDescription,
        ScreenNavigator.bounds(node), node.isEnabled, node.isClickable, node.isEditable,
        node.isVisibleToUser, node.isSelected, node.isChecked, node.isCheckable, node.isScrollable,
        node.childCount, node.actionList.map { it.id }
    ).joinToString("|")

    private data class Target(val node: AccessibilityNodeInfo? = null, val error: ToolResult? = null)

    private fun resolveTarget(
        owner: String, service: McpTouchAccessibilityService, arguments: JsonObject, selector: NodeSelector
    ): Target {
        val root = service.rootInActiveWindow
            ?: return Target(error = targetingError("no_window", "No active window; nothing was dispatched."))
        val window = observationWindow(service, root)
        val reference = arguments.string("element_ref")
        val node = if (reference != null) {
            val stored = references.resolve(owner, reference, window)
                ?: return Target(error = targetingError("stale_reference", "Reference is expired, foreign or stale; observe again."))
            val live = ScreenNavigator.atPath(root, stored.path)
            if (!stored.node.refresh() || live != stored.node || fingerprint(stored.node) != stored.fingerprint) {
                return Target(error = targetingError("stale_reference", "Element changed; observe again."))
            }
            stored.node
        } else {
            val matches = ScreenNavigator.findAll(service, selector.copy(nth = 0), MAX_QA_MATCHES, root)
            if (matches.size >= MAX_QA_MATCHES || (matches.size > 1 && arguments.int("nth") == null)) {
                return Target(error = targetingError("ambiguous_match",
                    "Selector matches ${matches.size} elements; use within or an explicit nth. Nothing was dispatched."))
            }
            matches.getOrNull(arguments.int("nth") ?: 0)
                ?: return Target(error = targetingError("no_match", "No element matches $selector."))
        }
        if (window != observationWindow(service, service.rootInActiveWindow) || !node.refresh()) {
            return Target(error = targetingError("stale_reference", "Window changed during resolution; observe again."))
        }
        if (!node.isEnabled || ScreenNavigator.visibleBounds(node, service).isEmpty || !ScreenNavigator.visibleByPolicy(node,
                arguments.string("visibility") ?: "fully_visible", service)) {
            return Target(error = targetingError("not_actionable", "Element is disabled or fails the visibility policy."))
        }
        if (node.windowId != root.windowId || node.packageName != root.packageName) {
            return Target(error = targetingError("stale_reference", "Element does not belong to the active window."))
        }
        return Target(node)
    }

    private suspend fun observeScreen(owner: String, arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val started = System.currentTimeMillis()
        val startMono = SystemClock.elapsedRealtime()
        val root = service.rootInActiveWindow ?: return targetingError("no_window", "No active window.")
        val window = observationWindow(service, root)
        val tree = ScreenNavigator.snapshot(root,
            (arguments.int("max_nodes") ?: DEFAULT_TREE_NODES).coerceIn(1, MAX_TREE_NODES))
        val treeAt = System.currentTimeMillis()
        val fingerprints = tree.nodes.map { ReferencedNode(it.node, it.path, fingerprint(it.node)) }
        val nodePayloads = tree.nodes.mapIndexed { index, item -> buildJsonObject {
            nodeJson(index, item.node, service).filterKeys { it != "match_index" }.forEach { (key, value) -> put(key, value) }
            put("node_index", index)
            putJsonArray("path") { item.path.forEach { add(it) } }
        } }
        val shot = if (arguments.bool("include_screenshot")) captureScreenshot(service,
            (arguments.int("max_dimension") ?: DEFAULT_SCREENSHOT_DIMENSION)
                .coerceIn(MIN_SCREENSHOT_DIMENSION, MAX_SCREENSHOT_DIMENSION), root.windowId) else null
        val nodesChanged = fingerprints.any { !it.node.refresh() || fingerprint(it.node) != it.fingerprint }
        val endWindow = observationWindow(service, service.rootInActiveWindow)
        val unstable = window != endWindow || nodesChanged
        val recorded = references.record(owner, window, if (unstable) emptyList() else fingerprints)
        val payload = buildJsonObject {
            put("observation_id", recorded.first)
            put("capture_started_at_ms", started); put("tree_captured_at_ms", treeAt)
            put("capture_finished_at_ms", System.currentTimeMillis())
            put("elapsed_ms", SystemClock.elapsedRealtime() - startMono)
            put("atomic", false); put("unstable", unstable); put("truncated", tree.truncated)
            put("foreground_package", window.packageName); put("window_id", window.windowId)
            put("window_generation", window.generation); put("rotation", window.rotation)
            put("end_window_generation", endWindow.generation)
            put("end_foreground_package", endWindow.packageName)
            putJsonArray("instability_reasons") {
                if (window != endWindow) add("window_changed")
                if (nodesChanged) add("nodes_changed")
            }
            put("orientation", when (service.resources.configuration.orientation) {
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                android.content.res.Configuration.ORIENTATION_PORTRAIT -> "portrait"
                else -> "undefined"
            })
            put("screen_width", window.width); put("screen_height", window.height)
            put("reference_lifetime_ms", 30_000)
            putJsonObject("webview") {
                put("count", tree.webViews)
                put("with_readable_accessibility", tree.readableWebViews)
                put("method", "android_accessibility")
                put("dom_available", false)
            }
            putJsonArray("nodes") {
                nodePayloads.forEachIndexed { index, item ->
                    add(JsonObject(item +
                        if (!unstable) mapOf("element_ref" to JsonPrimitive(recorded.second[index])) else emptyMap()))
                }
            }
            putJsonObject("screenshot") {
                put("status", if (shot == null) "not_requested" else if (shot.isSuccess) "captured" else "failed")
                shot?.exceptionOrNull()?.let { put("error", it.message ?: "Screenshot unavailable.") }
                shot?.getOrNull()?.let {
                    put("coordinate_space", "window")
                    put("origin_x", it.originX); put("origin_y", it.originY)
                    put("width", it.width); put("height", it.height)
                    put("screen_width", it.screenWidth); put("screen_height", it.screenHeight)
                    put("scale_x", it.screenWidth.toDouble() / it.width)
                    put("scale_y", it.screenHeight.toDouble() / it.height)
                }
            }
        }
        return ToolResult(payload.toString(), isError = shot?.isFailure == true,
            imageBase64 = shot?.getOrNull()?.let { java.util.Base64.getEncoder().encodeToString(it.jpegBytes) },
            imageMimeType = if (shot?.isSuccess == true) "image/jpeg" else null, structuredContent = payload)
    }

    private suspend fun waitForState(arguments: JsonObject): ToolResult {
        val service = touchService() ?: return touchServiceMissing()
        val predicates = (arguments["all"] as? JsonArray)?.map {
            it as? JsonObject ?: return targetingError("invalid_condition", "all entries must be objects.")
        } ?: return targetingError("invalid_condition", "all must be an array.")
        if (predicates.size !in 1..10) return targetingError("invalid_condition", "Provide 1-10 predicates.")
        val allowed = setOf("condition", "text", "text_exact", "resource_id", "content_desc", "class_name",
            "within", "visibility", "expected", "text_mode", "ignore_case", "quiet_ms")
        for (predicate in predicates) {
            if (predicate.keys.any { it !in allowed } ||
                predicate.string("condition") !in setOf("foreground", "visible", "absent", "text", "idle")) {
                return targetingError("invalid_condition", "Unsupported combined predicate or field.")
            }
            if (predicate.any { (key, value) -> when (key) {
                    "within" -> value !is JsonObject
                    "text_exact", "ignore_case" -> value !is JsonPrimitive || value.isString ||
                        value.content !in setOf("true", "false")
                    "quiet_ms" -> value !is JsonPrimitive || value.isString ||
                        value.content.toIntOrNull()?.let { it in 200..5000 } != true
                    else -> value !is JsonPrimitive || !value.isString
                } }) return targetingError("invalid_condition", "Predicate field has an invalid type or range.")
            try { validateTargeting("wait_for_state", predicate) } catch (error: IllegalArgumentException) {
                return targetingError("invalid_condition", error.message ?: "Invalid predicate.")
            }
            val condition = predicate.string("condition")
            if (condition !in setOf("foreground", "idle") && qaSelector(predicate).isEmpty) {
                return targetingError("invalid_condition", "Element predicates require a selector.")
            }
            if (condition == "text" && predicate.rawString("expected") == null && predicate.string("text") == null) {
                return targetingError("invalid_condition", "Text predicate needs expected or a text selector.")
            }
            if (predicate.string("text_mode") !in setOf(null, "exact", "contains")) {
                return targetingError("invalid_condition", "text_mode must be exact or contains.")
            }
        }
        val started = SystemClock.elapsedRealtime()
        val startAt = System.currentTimeMillis()
        val timeout = (arguments.int("timeout_ms") ?: 5000).coerceIn(250, 30_000)
        val interval = (arguments.int("interval_ms") ?: 200).coerceIn(100, 2000)
        val deadline = started + timeout
        var target = arguments.string("package_name") ?: service.rootInActiveWindow?.packageName?.toString()
        var hash: Int? = null
        var changedAt = started
        var results = emptyList<JsonObject>()
        var passed: Boolean
        var unstablePoll = false
        do {
            val root = service.rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (target == null && pkg != null) target = pkg
            val now = SystemClock.elapsedRealtime()
            val generation = service.windowGeneration.get()
            val treeHash = if (predicates.any { it.string("condition") == "idle" } && root != null)
                ScreenNavigator.snapshot(root, MAX_TREE_NODES).nodes.joinToString { fingerprint(it.node) }.hashCode() else null
            if (treeHash != hash || root == null || pkg != target) {
                hash = treeHash; changedAt = now
            }
            results = predicates.mapIndexed { index, predicate ->
                val condition = predicate.string("condition")!!
                val outcome = when (condition) {
                    "foreground" -> PredicateOutcome(root != null && pkg == target, when {
                        root == null -> "no_window"; pkg != target -> "wrong_package"; else -> "matched"
                    }, emptyList())
                    "idle" -> {
                        val ready = root != null && pkg == target && now - changedAt >=
                            (predicate.int("quiet_ms") ?: 700).coerceIn(200, 5000)
                        PredicateOutcome(ready, when {
                            root == null -> "no_window"; pkg != target -> "wrong_package"; !ready -> "not_idle"; else -> "matched"
                        }, emptyList())
                    }
                    else -> {
                        val matches = ScreenNavigator.findAll(service, qaSelector(predicate), MAX_QA_MATCHES, root)
                        UiPredicate.evaluate(condition, matches.map { if (it.isPassword) null else it.text?.toString() },
                            predicate.rawString("expected"), root != null, pkg, target,
                            truncated = matches.size >= MAX_QA_MATCHES,
                            contains = predicate.string("text_mode") == "contains", ignoreCase = predicate.bool("ignore_case"))
                    }
                }
                JsonObject(outcome.structured(condition, predicate.rawString("expected"), now - started) +
                    mapOf("predicate_index" to JsonPrimitive(index), "observed_at_ms" to JsonPrimitive(System.currentTimeMillis())))
            }
            val changedDuringPoll = generation != service.windowGeneration.get() ||
                root?.windowId != service.rootInActiveWindow?.windowId
            unstablePoll = changedDuringPoll
            if (changedDuringPoll) results = results.map { result ->
                JsonObject(result + buildJsonObject { put("passed", false); put("reason", "unstable_observation") })
            }
            passed = !changedDuringPoll && results.all { it["passed"] == JsonPrimitive(true) }
            val terminal = results.any { it.string("reason") in setOf("ambiguous_match", "truncated_count") }
            if (passed || terminal || SystemClock.elapsedRealtime() >= deadline) break
            delay(minOf(interval.toLong(), (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
        } while (true)
        val payload = buildJsonObject {
            put("passed", passed); put("reason", if (passed) "matched" else "conditions_not_met")
            put("capture_started_at_ms", startAt); put("capture_finished_at_ms", System.currentTimeMillis())
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            put("target_package", target ?: "unknown")
            put("foreground_package", service.rootInActiveWindow?.packageName?.toString() ?: "unknown"); put("atomic", false)
            put("unstable", unstablePoll)
            put("conditions", JsonArray(results))
            putJsonArray("failed_conditions") {
                results.forEachIndexed { index, result -> if (result["passed"] != JsonPrimitive(true)) add(index) }
            }
        }
        return ToolResult(payload.toString(), isError = !passed, structuredContent = payload)
    }

    private fun buildIdentity(arguments: JsonObject): ToolResult {
        val pkg = arguments.string("package_name")
            ?: return targetingError("invalid_package", "package_name is required.")
        if (!PACKAGE_NAME_REGEX.matches(pkg)) return targetingError("invalid_package", "Invalid package name.")
        val info = try { context.packageManager.getPackageInfo(pkg, 0) }
        catch (_: PackageManager.NameNotFoundException) {
            return targetingError("package_unavailable", "Package is not installed or visible.")
        }
        val data = buildJsonObject {
            put("package_name", pkg); put("version_code", info.longVersionCode)
            put("version_name", info.versionName ?: "unknown")
            put("sha", JsonNull); put("ci_artifact", JsonNull)
            put("provenance_status", "unknown")
            put("provenance_reason", "No target-app provenance contract is implemented; install dates do not establish a fix.")
        }
        return ToolResult(data.toString(), structuredContent = data)
    }

    private suspend fun collectFailureBundle(owner: String, runId: String, pkg: String, includeScreenshot: Boolean): ToolResult {
        require(PACKAGE_NAME_REGEX.matches(pkg)) { "Invalid package_name." }
        val started = System.currentTimeMillis()
        val sources = mutableListOf<JsonObject>()
        fun source(name: String, status: String, data: JsonObject = buildJsonObject {}) {
            sources += buildJsonObject {
                put("source", name); put("status", status); put("observed_at_ms", System.currentTimeMillis())
                put("data", data)
            }
        }
        suspend fun captureSource(name: String, block: suspend () -> Unit) {
            try {
                if (withTimeoutOrNull(2500) { block(); true } != true) source(name, "timed_out")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("TesterEvidence", "Unable to collect source $name", error)
                source(name, "failed", buildJsonObject { put("reason", "source_collection_failed") })
            }
        }
        val service = touchService()
        val root = service?.rootInActiveWindow
        if (service == null) source("observation", "denied")
        else if (root == null) source("observation", "failed")
        else if (root.packageName?.toString() != pkg) source("observation", "failed",
            buildJsonObject { put("reason", "wrong_package") })
        else {
            captureSource("observation") {
                val observation = observeScreen(owner, buildJsonObject { put("max_nodes", 200) })
                check(!observation.isError) { "Observed window became unavailable." }
                val raw = observation.structuredContent!!
                check(raw.string("foreground_package") == pkg) { "Observed package changed." }
                val safeNodes = raw.getValue("nodes").jsonArray.map { node ->
                    JsonObject(node.jsonObject.filterKeys { it in setOf("node_index", "path", "bounds", "visible_bounds",
                        "accessibility_visible", "viewport_intersecting", "fully_visible", "enabled", "editable", "scrollable") })
                }
                source("observation", if (raw["unstable"] == JsonPrimitive(true)) "failed" else "redacted",
                    JsonObject(raw.filterKeys { it !in setOf("nodes") } +
                    ("nodes" to JsonArray(safeNodes))))
            }
        }
        source("device_state", "collected", buildJsonObject {
            put("api_level", Build.VERSION.SDK_INT)
            put("rotation", service?.let { observationWindow(it, root).rotation } ?: -1)
            put("snapshot_available", deviceState.hasSnapshot())
            put("screen_interactive", context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        })
        captureSource("build_identity") {
            val identity = buildIdentity(buildJsonObject { put("package_name", pkg) })
            source("build_identity", if (identity.isError) "failed" else "collected",
                identity.structuredContent?.let { JsonObject(it.filterKeys { field -> field != "version_name" }) }
                    ?: buildJsonObject {})
        }
        for (buffer in listOf("main", "crash")) {
            val name = if (buffer == "main") "recent_logs" else "crash_buffer"
            captureSource(name) {
                val logResult = FailureLogSummary.collect(context, pkg, buffer)
                source(name, logResult.string("status")!!, logResult)
            }
        }
        if (!includeScreenshot) source("screenshot", "redacted", buildJsonObject {
            put("reason", "privacy_default; reliable image masking is unavailable")
        })
        else if (service == null) source("screenshot", "denied")
        else if (root == null || root.packageName?.toString() != pkg) source("screenshot", "failed")
        else {
            captureSource("screenshot") {
                val active = service.rootInActiveWindow
                check(active?.windowId == root.windowId && active?.packageName?.toString() == pkg) {
                    "Window changed before evidence capture."
                }
                val shot = captureScreenshot(service, 900, root.windowId)
                if (shot.isFailure) source("screenshot", "failed", buildJsonObject { put("reason", "capture_denied_or_unavailable") })
                else {
                    val image = shot.getOrThrow()
                    val id = durable.store.artifact(runId, "image/jpeg", image.jpegBytes)
                    source("screenshot", "collected", buildJsonObject {
                        put("artifact_id", id); put("width", image.width); put("height", image.height)
                        put("redacted", false); put("explicit_unmasked_capture", true)
                    })
                }
            }
        }
        val run = durable.store.update(runId) { it }
        val manifest = buildJsonObject {
            put("run_id", runId); put("capture_started_at_ms", started)
            put("capture_finished_at_ms", System.currentTimeMillis())
            put("text_policy", "all_free_text_omitted")
            put("primary_run_status", run.getValue("status"))
            put("primary_steps", run.getValue("steps"))
            put("sources", JsonArray(sources))
        }
        val artifact = durable.store.artifact(runId, "application/json", manifest.toString().toByteArray())
        val data = JsonObject(manifest + ("artifact_id" to JsonPrimitive(artifact)))
        val incomplete = sources.any { it.string("status") in setOf("failed", "denied", "unsupported", "timed_out") }
        durable.store.update(runId) { run -> JsonObject(run + buildJsonObject {
            put("failure_bundle", artifact)
            put("evidence_status", if (incomplete) "partial" else "collected")
        }) }
        return ToolResult(data.toString(), isError = incomplete, structuredContent = data)
    }

    private fun exportRun(arguments: JsonObject): ToolResult {
        val run = durable.owned(arguments)
        val format = arguments.string("format") ?: "json"
        require(format in setOf("json", "markdown")) { "format must be json or markdown." }
        val content = if (format == "json") run.toString() else buildString {
            appendLine("# Tester run ${run.string("run_id")}")
            appendLine("\nStatus: ${run.string("status")}\n\nFree text and step arguments are redacted.\n")
            appendLine("| Step | Tool | Status | Duration (ms) |")
            appendLine("| --- | --- | --- | --- |")
            run.getValue("steps").jsonArray.forEach { raw ->
                val step = raw.jsonObject
                appendLine("| ${step.string("id")} | ${step.string("tool")} | ${step.string("status")} | ${step.string("duration_ms") ?: ""} |")
            }
        }
        val id = durable.store.artifact(run.string("run_id")!!,
            if (format == "json") "application/json" else "text/markdown", content.toByteArray())
        val data = buildJsonObject { put("artifact_id", id); put("format", format); put("size_bytes", content.toByteArray().size) }
        return ToolResult(data.toString(), structuredContent = data)
    }

    private fun readArtifact(arguments: JsonObject): ToolResult {
        val run = durable.owned(arguments)
        val id = arguments.string("artifact_id") ?: error("artifact_id is required.")
        val artifact = durable.store.readArtifact(run.string("run_id")!!, id)
            ?: return targetingError("artifact_unavailable", "Artifact unavailable for this run.")
        if (artifact.first == "image/jpeg") return ToolResult("Unmasked image explicitly captured for owned run.",
            imageBase64 = java.util.Base64.getEncoder().encodeToString(artifact.second), imageMimeType = artifact.first)
        return ToolResult(artifact.second.toString(Charsets.UTF_8))
    }

    fun stopRuns() { if (durableDelegate.isInitialized()) durable.stop() }

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
        val routeExecutionMutex = Mutex()
        val ROUTE_SERIALIZED_TOOLS = setOf(
            "touch_test", "launch_app", "swipe", "get_screen_tree", "take_screenshot", "observe_screen",
            "click_element", "input_text", "scroll", "global_action", "ui_find", "ui_wait",
            "ui_assert", "ui_gesture", "device_state", "get_device_state", "ui_diff", "take_photo",
            "qa_session", "audio_instruction", "wait_for_state", "collect_failure_bundle"
        )
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
        val LOG_PID_REGEX = Regex("^\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+[VDIWEFA]\\s")
        const val MAX_LISTED_APPS = 200
        const val DEFAULT_LAUNCH_WAIT_MS = 8_000
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
