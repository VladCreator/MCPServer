package com.vlad.mcpserver.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import android.view.WindowManager
import com.vlad.mcpserver.McpTouchAccessibilityService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** One device-wide journal; recovery survives server/process restarts without automatic replay. */
class AndroidDeviceStateController private constructor(private val context: Context) {
    private val journal = AtomicFile(File(context.noBackupFilesDir, "device-state-recovery.json"))
    private var wakeLock: PowerManager.WakeLock? = null
    private val recovery = StateRecovery(::load, ::save, ::preflight, ::read, ::writeAndVerify) { message, error ->
        Log.e("TesterState", message, error)
    }

    fun hasSnapshot(): Boolean = recovery.pending()
    fun status(owner: String): ToolResult = recovery.status(owner)
    suspend fun restore(owner: String): ToolResult = withContext(NonCancellable) {
        withTimeoutOrNull(5_000) { recovery.restore(owner) } ?: ToolResult(
            "Restoration budget exhausted; pending fields retained.", isError = true,
            structuredContent = buildJsonObject { put("reason", "restore_timeout"); put("pending_cleanup", true) })
    }

    fun owner(key: String?): String {
        require(key != null && Regex("[A-Za-z0-9_-]{32,128}").matches(key)) {
            "state_key must be a random URL-safe secret, 32-128 characters."
        }
        return RunStore.hash("recovery:$key")
    }

    suspend fun set(owner: String, arguments: JsonObject): ToolResult {
        val fields = arguments.filterKeys { it in FIELDS }
        require(fields.isNotEmpty()) { "Provide at least one device-state field." }
        val values = fields.mapValues { (field, value) ->
            val primitive = value as? JsonPrimitive ?: error("$field has an invalid type.")
            when (field) {
                "rotation" -> {
                    require(primitive.isString)
                    val rotation = listOf("portrait", "landscape", "reverse_portrait", "reverse_landscape")
                        .indexOf(primitive.content)
                    require(rotation >= 0 || primitive.content == "auto") { "Invalid rotation." }
                    buildJsonObject {
                        put("auto", if (rotation < 0) 1 else 0)
                        put("user", if (rotation < 0) systemInt(Settings.System.USER_ROTATION, 0) else rotation)
                    }
                }
                "font_scale" -> {
                    val scale = primitive.floatOrNull
                    require(!primitive.isString && scale != null && scale.isFinite() && scale in .85f..2f) {
                        "font_scale must be 0.85-2.0."
                    }
                    JsonPrimitive(scale)
                }
                "volume" -> {
                    val percent = primitive.intOrNull
                    require(!primitive.isString && percent != null && percent in 0..100) { "volume must be 0-100." }
                    JsonPrimitive((percent / 100f * audio().getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt())
                }
                "animations" -> {
                    require(!primitive.isString && primitive.booleanOrNull != null)
                    val scale = if (primitive.boolean) 1f else 0f
                    buildJsonObject { ANIMATIONS.forEach { put(it, scale) } }
                }
                "keep_awake" -> {
                    require(!primitive.isString && primitive.booleanOrNull != null)
                    primitive
                }
                else -> primitive
            }
        }
        return recovery.set(owner, JsonObject(values))
    }

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun readText(): String = buildString {
        appendLine("Platform: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Manufacturer/Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Locale (Tester, not target app): ${context.resources.configuration.locales.toLanguageTags()}")
        appendLine("Foreground package: ${McpTouchAccessibilityService.instance()?.rootInActiveWindow?.packageName ?: "unknown"}")
        val metrics = context.resources.displayMetrics
        appendLine("Display: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi")
        appendLine("Effective rotation: ${context.getSystemService(WindowManager::class.java).defaultDisplay.rotation}")
        appendLine("Screen on: ${context.getSystemService(PowerManager::class.java).isInteractive}")
        appendLine("Accessibility service: ${McpTouchAccessibilityService.instance() != null}")
        appendLine("Media volume: ${audio().getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio().getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        for (field in FIELDS - "dark_mode") appendLine("$field: ${read(field)}")
        appendLine("Effective font scale: ${context.resources.configuration.fontScale}")
        appendLine("Effective dark mode: ${isDark()}")
        appendLine("WRITE_SETTINGS granted: ${Settings.System.canWrite(context)}")
        appendLine("WRITE_SECURE_SETTINGS granted: ${canWriteSecureSettings()}")
        append("Pending device recovery: ${hasSnapshot()}")
    }

    private fun preflight(field: String) {
        require(field in FIELDS) { "Unsupported state field." }
        if (field == "dark_mode") error(
            "Global dark mode is unsupported: writing ui_night_mode does not prove effective UI state. Use an explicitly authorized host adapter.")
        if (field in setOf("rotation", "font_scale")) check(Settings.System.canWrite(context)) {
            "WRITE_SETTINGS required; explicitly grant Modify system settings to Tester."
        }
        if (field == "animations") check(canWriteSecureSettings()) {
            "WRITE_SECURE_SETTINGS required; provision explicitly through ADB."
        }
        if (field == "volume") check(!audio().isVolumeFixed) { "Device policy fixes media volume." }
    }

    private fun read(field: String): JsonElement = when (field) {
        "rotation" -> buildJsonObject {
            put("auto", systemValue(Settings.System.ACCELEROMETER_ROTATION, integer = true))
            put("user", systemValue(Settings.System.USER_ROTATION, integer = true))
        }
        "font_scale" -> systemValue(Settings.System.FONT_SCALE)
        "animations" -> buildJsonObject { ANIMATIONS.forEach {
            val raw = Settings.Global.getString(context.contentResolver, it)
            put(it, if (raw == null) JsonNull else JsonPrimitive(raw.toFloat()))
        } }
        "volume" -> JsonPrimitive(audio().getStreamVolume(AudioManager.STREAM_MUSIC))
        "keep_awake" -> JsonPrimitive(wakeLock?.isHeld == true)
        else -> error("Unsupported state field.")
    }

    @Suppress("DEPRECATION")
    private suspend fun writeAndVerify(field: String, value: JsonElement) {
        when (field) {
            "rotation" -> {
                val values = value.jsonObject
                check(Settings.System.putString(context.contentResolver, Settings.System.USER_ROTATION,
                    values.getValue("user").jsonPrimitive.contentOrNull)) { "Rotation value rejected." }
                check(Settings.System.putString(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                    values.getValue("auto").jsonPrimitive.contentOrNull)) { "Rotation policy rejected." }
            }
            "font_scale" -> check(Settings.System.putString(context.contentResolver, Settings.System.FONT_SCALE,
                value.jsonPrimitive.contentOrNull)) { "Font scale rejected." }
            "animations" -> value.jsonObject.forEach { (key, scale) ->
                check(Settings.Global.putString(context.contentResolver, key, scale.jsonPrimitive.contentOrNull)) {
                    "Animation setting rejected."
                }
            }
            "volume" -> audio().setStreamVolume(AudioManager.STREAM_MUSIC, value.jsonPrimitive.int, 0)
            "keep_awake" -> {
                if (value.jsonPrimitive.boolean && wakeLock?.isHeld != true) {
                    wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Tester:QA"
                    ).apply { acquire(30 * 60 * 1000L) }
                } else if (!value.jsonPrimitive.boolean) {
                    wakeLock?.let { if (it.isHeld) it.release() }
                    wakeLock = null
                }
            }
            else -> error("Unsupported state field.")
        }
        repeat(20) {
            val settingMatches = read(field) == value
            val effectiveMatches = when (field) {
                "rotation" -> (value.jsonObject.getValue("auto").jsonPrimitive.intOrNull ?: 1) == 1 ||
                    context.getSystemService(WindowManager::class.java).defaultDisplay.rotation ==
                    (value.jsonObject.getValue("user").jsonPrimitive.intOrNull ?: 0)
                "font_scale" -> abs(context.resources.configuration.fontScale - (value.jsonPrimitive.floatOrNull ?: 1f)) < .01f
                else -> true
            }
            if (settingMatches && effectiveMatches) return
            delay(100)
        }
        error("Effective $field readback did not match; cleanup is required.")
    }

    private fun load(): JsonObject? = try {
        val raw = Json.parseToJsonElement(journal.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        if (raw is JsonNull) null else raw.jsonObject
    } catch (missing: java.io.FileNotFoundException) {
        if (journal.baseFile.exists()) throw missing else null
    }

    private fun save(state: JsonObject?) {
        val stream = journal.startWrite()
        try {
            stream.write((state ?: JsonNull).toString().toByteArray())
            stream.fd.sync()
            journal.finishWrite(stream)
            check(load() == state) { "Device recovery journal verification failed; no new state write may proceed." }
        } catch (error: Exception) {
            journal.failWrite(stream)
            throw error
        }
    }

    private fun systemInt(key: String, default: Int) = Settings.System.getInt(context.contentResolver, key, default)
    private fun systemValue(key: String, integer: Boolean = false): JsonElement {
        val raw = Settings.System.getString(context.contentResolver, key) ?: return JsonNull
        return if (integer) JsonPrimitive(raw.toInt()) else JsonPrimitive(raw.toFloat())
    }
    private fun audio(): AudioManager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private fun isDark() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

    companion object {
        private val FIELDS = setOf("rotation", "font_scale", "animations", "volume", "keep_awake", "dark_mode")
        private val ANIMATIONS = listOf(Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
        private var instance: AndroidDeviceStateController? = null
        @Synchronized fun get(context: Context): AndroidDeviceStateController =
            instance ?: AndroidDeviceStateController(context.applicationContext).also { instance = it }
    }
}
