package com.vlad.mcpserver.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tools available in the embedded iOS QA service. Cross-app UI automation is
 * intentionally absent: XCUITest owns real UI interaction, while this service
 * provides state, instructions and evidence from inside the host app.
 */
class IosQaToolExecutor(
    private val consent: suspend (clientId: String, toolName: String, arguments: JsonObject) -> Boolean =
        { _, _, _ -> true }
) : ToolExecutor {

    private val audio = IosAudioController()
    private val device = IosDeviceStateReader()
    private val qa = IosQaSessionManager()
    private val files = IosFileAccess()
    private val appInfo = IosAppInfo()
    private val notifications = IosNotificationController()
    private val camera = IosCameraController(files)
    private val health = IosHealthCheck(appInfo, files)

    override suspend fun execute(
        clientId: String,
        toolName: String,
        arguments: JsonObject
    ): ToolResult {
        if (McpTools.isSensitive(toolName) && !consent(clientId, toolName, arguments)) {
            return ToolResult("User rejected the tool call.", isError = true)
        }
        return when (toolName) {
            "get_device_info" -> ToolResult(device.readText())
            "get_device_state" -> ToolResult(device.readText())
            "health_check" -> ToolResult(health.read())
            "read_file" -> files.read(arguments.string("path").orEmpty())
            "create_notification" -> notifications.post(
                title = arguments.string("title").orEmpty(),
                message = arguments.string("message").orEmpty()
            )
            "take_photo" -> camera.capture()
            "get_app_version" -> appInfo.version(arguments.string("package_name"))
            "qa_session" -> qaSession(arguments)
            "audio_instruction" -> audioInstruction(arguments)
            "audio_state" -> withContext(Dispatchers.Main) { audio.state() }
            else -> ToolResult("Tool '$toolName' is not available on iOS.", isError = true)
        }
    }

    private fun qaSession(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase()
            ?: return ToolResult("Missing required argument: action", isError = true)
        return when (action) {
            "begin" -> {
                val testCase = arguments.string("test_case")
                    ?: return ToolResult("Missing required argument: test_case", isError = true)
                qa.begin(
                    testCase = testCase,
                    platform = arguments.string("platform") ?: "ios",
                    device = device.readText(),
                    notes = arguments.string("notes").orEmpty()
                )
            }
            "step" -> qa.step(
                title = arguments.string("title").orEmpty(),
                expected = arguments.string("expected").orEmpty(),
                actual = arguments.string("actual").orEmpty(),
                status = arguments.string("status").orEmpty(),
                notes = arguments.string("notes").orEmpty()
            )
            "status" -> qa.status()
            "end" -> qa.end(
                result = arguments.string("result").orEmpty(),
                notes = arguments.string("notes").orEmpty()
            )
            else -> ToolResult("action must be begin, step, status or end.", isError = true)
        }
    }

    private suspend fun audioInstruction(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase()
            ?: return ToolResult("Missing required argument: action", isError = true)
        return when (action) {
            "speak" -> withContext(Dispatchers.Main) {
                audio.speak(
                    text = arguments.string("text").orEmpty(),
                    language = arguments.string("language") ?: "en-US",
                    rate = arguments.float("rate") ?: 1.0f,
                    volumePercent = arguments.int("volume") ?: 100,
                    wait = arguments.bool("wait")
                )
            }
            "stop" -> withContext(Dispatchers.Main) { audio.stop() }
            "set_volume" -> ToolResult(
                "iOS does not expose a public API to change system media volume. " +
                    "Use the device buttons or the tester's headset.",
                isError = true
            )
            "play" -> ToolResult(
                "Audio-file playback is not implemented in the iOS QA service yet; use TTS.",
                isError = true
            )
            else -> ToolResult("action must be speak, play, stop or set_volume.", isError = true)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toFloatOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"
}
