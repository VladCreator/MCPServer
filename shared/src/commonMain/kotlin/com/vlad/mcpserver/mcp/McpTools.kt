package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject

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
    val imageMimeType: String? = null,
    val structuredContent: JsonObject? = null
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
        description = "Return basic information about the device and the Tester.",
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
        description = "Capture a JPEG photo with the device camera. The Tester app must be " +
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
            "the MCP Touch Service in Android accessibility settings. On failure the error " +
            "explains why (no window, transition in progress, secure window...).",
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
        description = "Launch an installed app by package name (e.g. com.android.settings) or by " +
            "its visible name (e.g. Chrome). Provide package_name or app_name; use list_apps to " +
            "discover apps. Only apps with a launcher entry can be opened. By default it waits " +
            "until the app is in the foreground and its UI settled (needs MCP Touch Service).",
        inputSchema = schema(emptyList()) {
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "Android package name of the app to launch")
            }
            putJsonObject("app_name") {
                put("type", "string")
                put("description", "Visible app name (case-insensitive; exact match wins, otherwise must be unambiguous)")
            }
            putJsonObject("wait_ms") {
                put("type", "integer")
                put("description", "Max time to wait for the app to be in front and idle (default 8000, max 30000, 0 disables)")
            }
        },
        sensitive = true,
        requirement = "Foreground, overlay permission or MCP Touch Service"
    )

    val listApps: ToolDefinition = ToolDefinition(
        name = "list_apps",
        description = "List installed apps that have a launcher entry (visible name and package " +
            "name), optionally filtered by a case-insensitive query.",
        inputSchema = schema(emptyList()) {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Optional filter matched against the app name or package name")
            }
        },
        requirement = "None"
    )

    val healthCheck: ToolDefinition = ToolDefinition(
        name = "health_check",
        description = "Lightweight server health probe: returns server time, uptime, app version " +
            "Touch Service state and granted special access (write settings, secure settings, " +
            "logs, overlay). Call it to verify the connection " +
            "before a sequence of actions, and retry the transport if it fails.",
        inputSchema = schema(emptyList()) {},
        requirement = "None"
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
        description = "Capture the current screen as a JPEG image, after waiting for the UI to " +
            "settle. The result text includes the capture timestamp and foreground package. Use " +
            "when get_screen_tree is empty or not enough (games, maps, canvas). Requires MCP Touch Service.",
        inputSchema = schema(emptyList()) {
            putJsonObject("max_dimension") {
                put("type", "integer")
                put("description", "Longest image side in pixels (default 1280, 320-2400)")
            }
            putJsonObject("settle_ms") {
                put("type", "integer")
                put("description", "Max time to wait for the UI to stop changing before capture (default 1500, 0 disables)")
            }
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "Also wait until this app is in the foreground (use right after launching an app)")
            }
        },
        sensitive = true,
        requirement = "MCP Touch Service; unavailable on secure screens"
    )

    val observeScreen: ToolDefinition = ToolDefinition(
        name = "observe_screen",
        description = "Observe the foreground Android window: bounded accessibility nodes with session-bound " +
            "element_ref values, visible bounds, window generation, package, rotation, dimensions and timestamps. " +
            "Tree-only by default; optional screenshot includes scaling and explicit capture failure. " +
            "Capture is not atomic; unstable observations have no actionable references. References expire " +
            "after 30 seconds, a new observation by this session, UI changes or an action. No DOM/OCR fallback.",
        inputSchema = schema(emptyList()) {
            putJsonObject("include_screenshot") { put("type", "boolean"); put("description", "Default false") }
            putJsonObject("max_nodes") { put("type", "integer"); put("description", "Default 300, max 1000") }
            putJsonObject("max_dimension") { put("type", "integer"); put("description", "Image longest side, 320-2400") }
        },
        sensitive = true,
        requirement = "MCP Touch Service; secure windows deny image capture"
    )

    val clickElement: ToolDefinition = ToolDefinition(
        name = "click_element",
        description = "Click (or long-press) a UI element." + SELECTOR_HINT +
            " If several elements match and neither index nor nth is given, nothing is clicked " +
            "and an ambiguity error is returned. Use within to scope to an ancestor container. Elements " +
            "without a click action are tapped at their center.",
        inputSchema = schema(emptyList()) {
            selectorProperties()
            putJsonObject("text_exact") { put("type", "boolean"); put("description", "Require the whole text to match (default false: partial)") }
            putJsonObject("class_name") { put("type", "string"); put("description", "Simple or full class name") }
            putJsonObject("nth") {
                put("type", "integer")
                put("description", "0-based position among the matches; same numbering as ui_find match_index")
            }
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
        description = "Perform a system navigation action (back, home...), optionally repeated with a pause. Requires MCP Touch Service.",
        required = listOf("action"),
        inputSchema = schema(listOf("action")) {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { GLOBAL_ACTIONS.forEach { add(it) } }
            }
            putJsonObject("count") {
                put("type", "integer")
                put("description", "Repeat the action this many times (1-50, default 1), e.g. back x2")
            }
            putJsonObject("interval_ms") {
                put("type", "integer")
                put("description", "Pause between repetitions in ms (default 300, max 5000)")
            }
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )


    val swipe: ToolDefinition = ToolDefinition(
        name = "swipe",
        description = "Swipe across the screen by direction (finger movement: up, down, left, " +
            "right) or between explicit coordinates. Control speed with duration_ms and length " +
            "with distance (fraction of the screen, 0.1-0.9). Optionally repeat with count. " +
            "Requires MCP Touch Service.",
        inputSchema = schema(emptyList()) {
            putJsonObject("direction") {
                put("type", "string")
                putJsonArray("enum") { SCROLL_DIRECTIONS.forEach { add(it) } }
                put("description", "Direction the finger moves")
            }
            putJsonObject("from_x") { put("type", "integer") }
            putJsonObject("from_y") { put("type", "integer") }
            putJsonObject("to_x") { put("type", "integer") }
            putJsonObject("to_y") { put("type", "integer") }
            putJsonObject("distance") { put("type", "number"); put("description", "Fraction of the screen (default 0.6)") }
            putJsonObject("duration_ms") { put("type", "integer"); put("description", "Gesture duration; smaller is faster (default 300, 50-5000)") }
            putJsonObject("count") { put("type", "integer"); put("description", "Repeat 1-50 times (default 1)") }
            putJsonObject("interval_ms") { put("type", "integer"); put("description", "Pause between repetitions (default 300)") }
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val getAppVersion: ToolDefinition = ToolDefinition(
        name = "get_app_version",
        description = "Return versionName, versionCode and install/update times of an installed " +
            "app (launchable apps and Tester itself).",
        required = listOf("package_name"),
        inputSchema = schema(listOf("package_name")) {
            putJsonObject("package_name") { put("type", "string"); put("description", "Android package name") }
        },
        requirement = "None"
    )

    val getLogs: ToolDefinition = ToolDefinition(
        name = "get_logs",
        description = "Read recent logcat lines, optionally for one package and minimum level. " +
            "Set crashes=true to read the crash buffer (use for crash logs). Reading other apps' " +
            "logs needs: adb shell pm grant com.vlad.mcpserver android.permission.READ_LOGS",
        inputSchema = schema(emptyList()) {
            putJsonObject("package_name") { put("type", "string"); put("description", "Only lines from this app's processes, including earlier (crashed) ones still in the log buffer") }
            putJsonObject("level") { put("type", "string"); put("description", "Minimum level: V, D, I, W, E (default I)") }
            putJsonObject("query") { put("type", "string"); put("description", "Only lines containing this text") }
            putJsonObject("lines") { put("type", "integer"); put("description", "Max lines returned (default 200, max 1000)") }
            putJsonObject("crashes") { put("type", "boolean"); put("description", "Read the crash buffer instead of main") }
        },
        sensitive = true,
        requirement = "READ_LOGS granted via adb for other apps' logs"
    )

    val qaSession: ToolDefinition = ToolDefinition(
        name = "qa_session",
        description = "QA test session: begin, record a step, check status or end and write a JSON/Markdown report. " +
            "Use begin before a QA case, step for each action/assertion, and end to produce evidence.",
        required = listOf("action"),
        inputSchema = schema(listOf("action")) {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { add("begin"); add("step"); add("status"); add("end") }
            }
            putJsonObject("test_case") { put("type", "string"); put("description", "Test case name/ID for begin") }
            putJsonObject("title") { put("type", "string"); put("description", "Step title") }
            putJsonObject("expected") { put("type", "string"); put("description", "Expected result") }
            putJsonObject("actual") { put("type", "string"); put("description", "Observed result") }
            putJsonObject("status") { put("type", "string"); putJsonArray("enum") { add("pass"); add("fail"); add("blocked"); add("info") } }
            putJsonObject("result") { put("type", "string"); put("description", "Final result for end, e.g. PASS/FAIL/BLOCKED") }
            putJsonObject("notes") { put("type", "string") }
            putJsonObject("state_key") { put("type", "string"); put("description", "Recovery key for end cleanup if device_state changed fields; never recorded in reports.") }
        },
        requirement = "App sandbox for reports"
    )

    val uiFind: ToolDefinition = ToolDefinition(
        name = "ui_find",
        description = "Find all visible UI elements matching a selector. Returns count and JSON matches with text, " +
            "content description, resource id, class, bounds and flags. match_index is the value to pass as nth to " +
            "click_element (it stays absolute when nth is given here). Use instead of pulling the whole tree.",
        inputSchema = schema(emptyList()) {
            qaSelectorProperties(includeIndex = false)
            putJsonObject("nth") { put("type", "integer"); put("description", "Skip this many leading matches") }
            putJsonObject("include_invisible") { put("type", "boolean"); put("description", "Include invisible nodes (default false)") }
            putJsonObject("limit") { put("type", "integer"); put("description", "Max matches (default 20, max 100)") }
        },
        requirement = "MCP Touch Service enabled"
    )

    val uiWait: ToolDefinition = ToolDefinition(
        name = "ui_wait",
        description = "Wait until a UI condition is met: visible, absent, text or idle (tree stops changing). " +
            "Pass package_name to guard every condition against the wrong foreground app. " +
            "For text, expected compares the unique candidate's actual text (exact and case-sensitive by default). " +
            "Prefer this over polling get_screen_tree in a loop.",
        required = listOf("condition"),
        inputSchema = schema(listOf("condition")) {
            putJsonObject("condition") {
                put("type", "string")
                putJsonArray("enum") { add("visible"); add("absent"); add("text"); add("idle") }
            }
            qaSelectorProperties()
            textComparisonProperties()
            putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Default 5000, max 30000") }
            putJsonObject("interval_ms") { put("type", "integer"); put("description", "Poll interval, default 250") }
            putJsonObject("quiet_ms") { put("type", "integer"); put("description", "Idle: quiet period, default 700") }
            putJsonObject("package_name") { put("type", "string"); put("description", "Require this app for every condition; otherwise bind to the initial foreground app") }
        },
        requirement = "MCP Touch Service enabled"
    )

    val uiAssert: ToolDefinition = ToolDefinition(
        name = "ui_assert",
        description = "Assert a UI condition with a timeout: visible, absent, text or count. On failure returns " +
            "expected/actual, a screenshot and a tree excerpt.",
        required = listOf("condition"),
        inputSchema = schema(listOf("condition")) {
            putJsonObject("condition") {
                put("type", "string")
                putJsonArray("enum") { add("visible"); add("absent"); add("text"); add("count") }
            }
            qaSelectorProperties()
            textComparisonProperties()
            putJsonObject("package_name") { put("type", "string"); put("description", "Require this foreground app for the assertion") }
            putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Default 5000, max 30000") }
            putJsonObject("max_dimension") { put("type", "integer"); put("description", "Failure screenshot size, default 900") }
        },
        requirement = "MCP Touch Service enabled"
    )

    val uiGesture: ToolDefinition = ToolDefinition(
        name = "ui_gesture",
        description = "Dispatch a real gesture: tap, double_tap, multi_tap, long_press, swipe, drag or pinch. " +
            "Target coordinates (x/y) or a selector; use multi_tap for double-submit/rapid-tap QA.",
        required = listOf("type"),
        inputSchema = schema(listOf("type")) {
            putJsonObject("type") {
                put("type", "string")
                putJsonArray("enum") {
                    add("tap"); add("double_tap"); add("multi_tap"); add("long_press")
                    add("swipe"); add("drag"); add("pinch")
                }
            }
            qaSelectorProperties()
            putJsonObject("element_ref") { put("type", "string"); put("description", "Session-bound observation reference instead of a selector") }
            putJsonObject("nth") { put("type", "integer"); put("description", "Explicit 0-based match") }
            putJsonObject("x") { put("type", "number") }
            putJsonObject("y") { put("type", "number") }
            putJsonObject("to_x") { put("type", "number") }
            putJsonObject("to_y") { put("type", "number") }
            putJsonObject("count") { put("type", "integer"); put("description", "multi_tap count, 2-10") }
            putJsonObject("interval_ms") { put("type", "integer"); put("description", "multi_tap interval, default 120") }
            putJsonObject("duration_ms") { put("type", "integer"); put("description", "Gesture duration, default 50 (tap) / 350 (swipe)") }
            putJsonObject("direction") { put("type", "string"); putJsonArray("enum") { add("in"); add("out") } }
            putJsonObject("radius") { put("type", "number"); put("description", "Pinch end radius, default 220") }
        },
        sensitive = true,
        requirement = "MCP Touch Service enabled"
    )

    val audioInstruction: ToolDefinition = ToolDefinition(
        name = "audio_instruction",
        description = "Play QA instructions for the tester: speak text with the device TTS (English by default), " +
            "play an audio file, stop audio or set media volume.",
        required = listOf("action"),
        inputSchema = schema(listOf("action")) {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { add("speak"); add("play"); add("stop"); add("set_volume") }
            }
            putJsonObject("text") { put("type", "string"); put("description", "Text to speak") }
            putJsonObject("path") { put("type", "string"); put("description", "Audio file inside the app sandbox") }
            putJsonObject("language") { put("type", "string"); put("description", "BCP-47, default en-US") }
            putJsonObject("rate") { put("type", "number"); put("description", "Speech rate 0.5-2.0, default 1.0") }
            putJsonObject("volume") { put("type", "integer"); put("description", "0-100 for set_volume or playback") }
            putJsonObject("loop") { put("type", "boolean") }
            putJsonObject("wait") { put("type", "boolean"); put("description", "Wait for speech/file to finish") }
        },
        sensitive = true,
        requirement = "Device TTS/audio; no extra permission"
    )

    val audioState: ToolDefinition = ToolDefinition(
        name = "audio_state",
        description = "Read media volume, whether music is active, whether TTS is speaking and whether the QA " +
            "audio player is playing.",
        inputSchema = schema(emptyList()) { }
    )

    val getDeviceState: ToolDefinition = ToolDefinition(
        name = "get_device_state",
        description = "Read QA-relevant device state: Android version, model, locale, dark mode, rotation, font " +
            "scale, media volume, screen on, accessibility and foreground package.",
        inputSchema = schema(emptyList()) { }
    )

    val deviceState: ToolDefinition = ToolDefinition(
        name = "device_state",
        description = "Change or restore QA environment: rotation/auto-rotate, dark mode, font scale, animation " +
            "scales, media volume and keep-awake. Persists only touched fields, verifies effective values, " +
            "and retains failed restoration. Required secret state_key allows recovery after reconnect/restart. " +
            "Global dark mode is explicitly unsupported.",
        required = listOf("action", "state_key"),
        inputSchema = schema(listOf("action", "state_key")) {
            putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("set"); add("restore"); add("status") } }
            putJsonObject("state_key") { put("type", "string"); put("description", "Required random URL-safe recovery secret, 32-128 characters; retain before set, never log it.") }
            putJsonObject("rotation") {
                put("type", "string")
                putJsonArray("enum") { add("auto"); add("portrait"); add("landscape"); add("reverse_portrait"); add("reverse_landscape") }
            }
            putJsonObject("dark_mode") { put("type", "string"); putJsonArray("enum") { add("on"); add("off"); add("auto") } }
            putJsonObject("font_scale") { put("type", "number"); put("description", "0.85-2.0") }
            putJsonObject("animations") { put("type", "boolean"); put("description", "Enable/disable system animation scales") }
            putJsonObject("volume") { put("type", "integer"); put("description", "0-100 media volume") }
            putJsonObject("keep_awake") { put("type", "boolean") }
        },
        sensitive = true,
        requirement = "WRITE_SETTINGS/WRITE_SECURE_SETTINGS for rotation/dark/animations; volume/keep-awake otherwise"
    )

    val uiDiff: ToolDefinition = ToolDefinition(
        name = "ui_diff",
        description = "Save a visual baseline or compare the current screen with it. Returns a diff percentage; " +
            "use it for dark/light and rotation regressions.",
        required = listOf("action", "name"),
        inputSchema = schema(listOf("action", "name")) {
            putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("save_baseline"); add("compare") } }
            putJsonObject("name") { put("type", "string"); put("description", "Baseline name, alphanumeric/_/-") }
            putJsonObject("threshold") { put("type", "number"); put("description", "Max diff percent for PASS, default 1.0") }
            putJsonObject("max_dimension") { put("type", "integer"); put("description", "Capture size, default 1280") }
        },
        requirement = "MCP Touch Service; baseline stored in the app sandbox"
    )

    val runRoute = ToolDefinition(
        name = "run_route",
        description = "Execute one native scenario from YAML v1/v2, structured scenario or exact catalog route_id/version/digest. " +
            "Schema 2 supports typed parameters, preconditions, setup, postconditions and bounded cancellation-safe cleanup. " +
            "No action retries or implicit idle waits. Catalog policies pin the complete expanded plan.",
        inputSchema = schema(emptyList()) {
            putJsonObject("yaml") {
                put("type", "string")
                put("description", "Inline Tester YAML v1/v2, at most 32768 characters; mutually exclusive with scenario/route_id")
            }
            putJsonObject("scenario") { put("type", "object"); put("description", "Structured v1/v2 definition; same compiler as YAML") }
            catalogProperties()
            putJsonObject("policy_id") { put("type", "string"); put("description", "Optional session-scoped exact catalog-plan consent from route_policy") }
            putJsonObject("parameters") {
                put("type", "object")
                put("description", "Typed values for declared route parameters")
            }
            putJsonObject("mode") { put("type", "string"); putJsonArray("enum") { add("sync"); add("async") } }
            putJsonObject("run_key") { put("type", "string"); put("description", "Secret caller-generated random URL-safe recovery key, 32-128 characters; required for async/reliable retry. Never log it.") }
            putJsonObject("submission_id") { put("type", "string"); put("description", "Stable deduplication ID, 1-80 safe characters; required for async. Same key/ID with a different plan is rejected.") }
            putJsonObject("disconnect_policy") { put("type", "string"); put("description", "Async requires continue_until_terminal; accepted runs never replay on reconnect.") }
            putJsonObject("queue_timeout_ms") { put("type", "integer"); put("description", "Lock queue deadline, 250-30000, default 10000; separate from route execution and approval.") }
            putJsonObject("privacy_sensitive") { put("type", "boolean"); put("description", "Default true: omit route failure images and disallow unmasked evidence images. Set false only for approved non-sensitive synthetic data.") }
        },
        sensitive = true,
        requirement = "Android; MCP Touch Service; one approval for the complete route"
    )

    private fun JsonObjectBuilder.catalogProperties() {
        putJsonObject("route_id") { put("type", "string") }
        putJsonObject("version") { put("type", "string"); put("description", "Exact catalog major.minor.patch, no latest alias") }
        putJsonObject("digest") { put("type", "string"); put("description", "Exact SHA-256 of bundled definition from get_route") }
    }

    val listRoutes = ToolDefinition(
        name = "list_routes", description = "List immutable APK-bundled reviewed Android fixture scenarios with exact versions and SHA-256 digests.",
        inputSchema = schema(emptyList()) {}
    )
    val getRoute = ToolDefinition(
        name = "get_route", description = "Retrieve the exact reviewed catalog version and YAML; read-only, not execution authority.",
        required = listOf("route_id", "version"),
        inputSchema = schema(listOf("route_id", "version")) { catalogProperties() }
    )
    val routePolicy = ToolDefinition(
        name = "route_policy",
        description = "Grant, inspect or revoke bounded unattended consent for one exact expanded catalog plan, parameters and privacy policy. " +
            "Grant ALWAYS requires dashboard approval, even with sensitive confirmations disabled. Session/process scoped, 1-30 attempts, at most 15 minutes. No global bypass.",
        required = listOf("action"),
        inputSchema = schema(listOf("action")) {
            putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("grant"); add("status"); add("revoke") } }
            catalogProperties()
            putJsonObject("parameters") { put("type", "object") }
            putJsonObject("privacy_sensitive") { put("type", "boolean") }
            putJsonObject("policy_id") { put("type", "string") }
            putJsonObject("max_runs") { put("type", "integer"); put("description", "1-30, default 1; counts fresh attempts, never deduplication") }
            putJsonObject("ttl_ms") { put("type", "integer"); put("description", "1000-900000, default 600000") }
        },
        sensitive = true,
        requirement = "Explicit dashboard approval on grant; exact APK catalog plan; approved session only"
    )
    val getCapabilities = ToolDefinition(
        name = "get_capabilities",
        description = "Read Android tool support, current permission blockers, execution location and " +
            "mutation/restoration classifications. Conditional support is not permission to execute.",
        inputSchema = schema(emptyList()) {}
    )

    val waitForState = ToolDefinition(
        name = "wait_for_state",
        description = "Wait for all of 1-10 predicates using one foreground window per poll and one deadline. " +
            "Supports foreground, visible, absent, text and idle. Returns every predicate, failed indices and timestamps; " +
            "a measured match is not a promise of future stability. No action retries.",
        required = listOf("all"),
        inputSchema = schema(listOf("all")) {
            putJsonObject("all") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        qaSelectorProperties()
                        putJsonObject("condition") { put("type", "string"); putJsonArray("enum") {
                            add("foreground"); add("visible"); add("absent"); add("text"); add("idle")
                        } }
                        textComparisonProperties()
                        putJsonObject("quiet_ms") { put("type", "integer") }
                    }
                    put("additionalProperties", false)
                }
            }
            putJsonObject("package_name") { put("type", "string"); put("description", "Expected foreground; otherwise bind to initial/first app") }
            putJsonObject("timeout_ms") { put("type", "integer"); put("description", "250-30000, default 5000") }
            putJsonObject("interval_ms") { put("type", "integer"); put("description", "100-2000, default 200") }
        },
        requirement = "MCP Touch Service"
    )

    val getRun = ToolDefinition(
        name = "get_run",
        description = "Read a durable redacted route timeline. Requires run_id and secret run_key, even after reconnect. Never replays steps.",
        required = listOf("run_id", "run_key"),
        inputSchema = schema(listOf("run_id", "run_key")) { runOwnershipProperties() }
    )
    val cancelRun = ToolDefinition(
        name = "cancel_run",
        description = "Cancel an owned run cooperatively. Already dispatched actions cannot be undone. " +
            "cancellation_pending means it has not yet released device ownership; inspect get_run.",
        required = listOf("run_id", "run_key"),
        inputSchema = schema(listOf("run_id", "run_key")) { runOwnershipProperties() }
    )
    val collectFailureBundle = ToolDefinition(
        name = "collect_failure_bundle",
        description = "Capture bounded owned-run evidence: redacted observation geometry, device/build metadata, " +
            "filtered log/crash summaries and explicit source failures. All free text/log messages are omitted. " +
            "Screenshots are omitted by default; include_screenshot explicitly requests an UNMASKED window image " +
            "and must only be used with non-sensitive synthetic data. Original run failure is preserved.",
        required = listOf("run_id", "run_key", "package_name"),
        inputSchema = schema(listOf("run_id", "run_key", "package_name")) {
            runOwnershipProperties()
            putJsonObject("package_name") { put("type", "string") }
            putJsonObject("include_screenshot") { put("type", "boolean"); put("description", "Default false; explicit unmasked capture, no reliable masking") }
        },
        sensitive = true,
        requirement = "Owned run; screenshot requires accessibility and non-secure window; other-app logs need READ_LOGS and OS approval"
    )
    val exportRun = ToolDefinition(
        name = "export_run",
        description = "Export the owned canonical redacted run as JSON, Markdown timeline or JUnit XML. Returns opaque artifact_id, never an arbitrary path.",
        required = listOf("run_id", "run_key"),
        inputSchema = schema(listOf("run_id", "run_key")) {
            runOwnershipProperties()
            putJsonObject("format") { put("type", "string"); putJsonArray("enum") { add("json"); add("markdown"); add("junit") } }
        }
    )
    val getArtifact = ToolDefinition(
        name = "get_artifact",
        description = "Read an artifact scoped to a run and recovery key. Returns JSON/Markdown text or an explicitly captured image. Max 2 MiB.",
        required = listOf("run_id", "run_key", "artifact_id"),
        inputSchema = schema(listOf("run_id", "run_key", "artifact_id")) {
            runOwnershipProperties()
            putJsonObject("artifact_id") { put("type", "string") }
        },
        sensitive = true,
        requirement = "Run recovery key and owned artifact ID"
    )
    val getBuildIdentity = ToolDefinition(
        name = "get_build_identity",
        description = "Read installed package/version and optional schema-1 manifest provenance. App-reported SHA/artifact are unverified, not independent CI evidence. Missing provenance stays unknown.",
        required = listOf("package_name"),
        inputSchema = schema(listOf("package_name")) { putJsonObject("package_name") { put("type", "string") } }
    )

    val openLink = ToolDefinition(
        name = "open_link",
        description = "Open a hierarchical HTTP(S)/custom URI in one explicitly named app. Resolves exactly one " +
            "exported browsable handler, uses no arbitrary extras/flags and verifies foreground/quiet readiness. " +
            "A dispatched launch timeout is not safe to retry blindly. URIs are omitted from history.",
        required = listOf("package_name", "uri"),
        inputSchema = schema(listOf("package_name", "uri")) {
            putJsonObject("package_name") { put("type", "string") }
            putJsonObject("uri") { put("type", "string"); put("description", "Max 2048 characters; no credentials, file/content/intent/data/system schemes.") }
            putJsonObject("wait_ms") { put("type", "integer"); put("description", "250-30000, default 5000") }
        }, sensitive = true, requirement = "Accessibility for verified readiness; installed visible target with unique browsable handler"
    )
    val clipboard = ToolDefinition(
        name = "clipboard",
        description = "Consented write-first plain-text clipboard operations while Tester is foreground. " +
            "read_owned/clear_owned only access the clip written by this process; never snapshot/read/restore " +
            "unrelated clipboard contents. Text and results omitted from history.",
        required = listOf("action"),
        inputSchema = schema(listOf("action")) {
            putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("write"); add("read_owned"); add("clear_owned") } }
            putJsonObject("text") { put("type", "string"); put("description", "Plain text, max 5000; required for write") }
        }, sensitive = true, requirement = "Tester foreground; Android focus/policy restrictions apply"
    )
    val getTestEnvironment = ToolDefinition(
        name = "get_test_environment",
        description = "Inspect target manifest permission flags and inspectable app locale override. Report " +
            "app-ops/effective-locale limitations and unsupported permission/locale/location/reset/time adapters. " +
            "Does not grant permissions or modify target data.",
        required = listOf("package_name"),
        inputSchema = schema(listOf("package_name")) { putJsonObject("package_name") { put("type", "string") } }
    )

    val all: List<ToolDefinition> = listOf(
        getDeviceInfo,
        readFile,
        createNotification,
        takePhoto,
        touchTest,
        launchApp,
        listApps,
        healthCheck,
        getScreenTree,
        takeScreenshot,
        clickElement,
        inputText,
        scroll,
        globalAction,
        swipe,
        getAppVersion,
        getLogs,
        qaSession,
        uiFind,
        uiWait,
        uiAssert,
        uiGesture,
        audioInstruction,
        audioState,
        getDeviceState,
        deviceState,
        uiDiff,
        runRoute,
        getCapabilities,
        observeScreen,
        waitForState,
        getRun,
        cancelRun,
        collectFailureBundle,
        exportRun,
        getArtifact,
        getBuildIdentity,
        openLink,
        clipboard,
        getTestEnvironment,
        listRoutes,
        getRoute,
        routePolicy
    )

    fun available(): List<ToolDefinition> = all.filter { it.available }

    /**
     * iOS is an embedded QA service, not a cross-app automation host. Only the
     * tools that make sense for the host app are advertised.
     */
    fun iosAvailable(): List<ToolDefinition> = listOf(
        getDeviceInfo,
        healthCheck,
        readFile,
        createNotification,
        takePhoto,
        getAppVersion,
        getScreenTree,
        takeScreenshot,
        getLogs,
        qaSession.copy(inputSchema = JsonObject(qaSession.inputSchema + ("properties" to
            JsonObject(qaSession.inputSchema.getValue("properties").jsonObject - "state_key")))),
        audioInstruction,
        audioState,
        getDeviceState
    ).filter { it.available }

    fun find(name: String): ToolDefinition? = available().firstOrNull { it.name == name }

    fun isSensitive(name: String): Boolean = all.firstOrNull { it.name == name }?.sensitive == true

    private fun kotlinx.serialization.json.JsonObjectBuilder.qaSelectorProperties(includeIndex: Boolean = true) {
        targetingProperties()
        if (includeIndex) putJsonObject("index") { put("type", "integer"); put("description", "Index from the last get_screen_tree") }
        putJsonObject("text") { put("type", "string"); put("description", "Visible text (partial, case-insensitive)") }
        putJsonObject("resource_id") { put("type", "string"); put("description", "View id, full or suffix") }
        putJsonObject("content_desc") { put("type", "string"); put("description", "Content description (partial)") }
        putJsonObject("class_name") { put("type", "string"); put("description", "Simple or full class name") }
        putJsonObject("text_exact") { put("type", "boolean"); put("description", "Require exact text (default false)") }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.runOwnershipProperties() {
        putJsonObject("run_id") { put("type", "string") }
        putJsonObject("run_key") { put("type", "string"); put("description", "Secret recovery key from submission; never log it") }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.textComparisonProperties() {
        putJsonObject("expected") { put("type", "string"); put("description", "Expected actual text or count; omitted text expectation uses the text selector") }
        putJsonObject("text_mode") { put("type", "string"); putJsonArray("enum") { add("exact"); add("contains") }; put("description", "Comparison of expected against actual text; default exact") }
        putJsonObject("ignore_case") { put("type", "boolean"); put("description", "Expected text comparison ignores case (default false); independent of selector matching") }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.selectorProperties(
        textKey: String = "text",
        textDescription: String = "Visible text of the element (case-insensitive, partial match)"
    ) {
        targetingProperties(includeReference = true)
        putJsonObject("class_name") { put("type", "string"); put("description", "Simple or full class name") }
        putJsonObject("text_exact") { put("type", "boolean") }
        putJsonObject("nth") { put("type", "integer"); put("description", "Explicit 0-based match; otherwise actions require uniqueness") }
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.targetingProperties(includeReference: Boolean = false) {
        if (includeReference) putJsonObject("element_ref") {
            put("type", "string")
            put("description", "Opaque reference from observe_screen/ui_find; cannot combine with selectors")
        }
        putJsonObject("within") {
            put("type", "object")
            put("description", "Match an ancestor container, not a sibling label. Use container resource_id or content_desc.")
            putJsonObject("properties") {
                for (field in listOf("text", "resource_id", "content_desc", "class_name")) {
                    putJsonObject(field) { put("type", "string") }
                }
                putJsonObject("text_exact") { put("type", "boolean") }
            }
            put("additionalProperties", false)
        }
        putJsonObject("visibility") {
            put("type", "string")
            putJsonArray("enum") { add("accessibility"); add("intersecting"); add("fully_visible") }
            put("description", "Find/check default accessibility; actions default fully_visible. Bounds cannot establish overlay occlusion.")
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
