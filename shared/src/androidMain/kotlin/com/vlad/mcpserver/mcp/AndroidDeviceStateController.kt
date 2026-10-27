package com.vlad.mcpserver.mcp

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import com.vlad.mcpserver.McpTouchAccessibilityService
import java.util.Locale
import kotlin.math.roundToInt

/**
 * QA environment state: dark mode, rotation, font scale, animation scales,
 * media volume and a screen wake lock. Reads do not require permissions;
 * writes use WRITE_SETTINGS/WRITE_SECURE_SETTINGS and fail with an actionable
 * message when the QA build has not been granted those permissions.
 */
class AndroidDeviceStateController(private val context: Context) {

    private data class Snapshot(
        val autoRotate: Int,
        val userRotation: Int,
        val fontScale: Float,
        val nightMode: String?,
        val animatorScale: Float,
        val transitionScale: Float,
        val windowScale: Float,
        val volume: Int,
        val keepAwake: Boolean
    )

    private var saved: Snapshot? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun readText(): String {
        val audio = audioManager()
        val volume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        val currentPackage = runCatching {
            McpTouchAccessibilityService.instance()?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        val accessibility = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().contains(context.packageName)
        }.getOrDefault(false)
        return buildString {
            appendLine("Platform: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Manufacturer/Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("Dark mode: ${darkMode()} (secure=${secureNightMode() ?: "unset"})")
            appendLine("Rotation: ${rotationName()} (${rotation()})")
            appendLine("Font scale: ${"%.2f".format(configuration.fontScale)}")
            appendLine("Display: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi")
            appendLine("Media volume: $volume/$maxVolume")
            appendLine("Screen on: ${screenOn()}")
            appendLine("Accessibility service: $accessibility")
            appendLine("Foreground package: ${currentPackage ?: "unknown"}")
            appendLine("Animations: ${animationsEnabled()}")
            appendLine("Keep awake: ${wakeLock?.isHeld == true}")
            appendLine("WRITE_SETTINGS granted: ${Settings.System.canWrite(context)}")
            append("Snapshot available: ${saved != null}")
        }
    }

    fun hasSnapshot(): Boolean = saved != null

    fun snapshot(): String {
        saved = Snapshot(
            autoRotate = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                1
            ),
            userRotation = Settings.System.getInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                0
            ),
            fontScale = Settings.System.getFloat(
                context.contentResolver,
                Settings.System.FONT_SCALE,
                1.0f
            ),
            nightMode = runCatching {
                Settings.Secure.getString(context.contentResolver, "ui_night_mode")
            }.getOrNull(),
            animatorScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ),
            transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f
            ),
            windowScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                1.0f
            ),
            volume = audioManager()?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
            keepAwake = wakeLock?.isHeld == true
        )
        return readText()
    }

    fun setRotation(value: String): String {
        requireSystemWrite()
        when (value.lowercase()) {
            "auto" -> Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                1
            )
            "portrait" -> setFixedRotation(0)
            "landscape" -> setFixedRotation(1)
            "reverse_portrait" -> setFixedRotation(2)
            "reverse_landscape" -> setFixedRotation(3)
            else -> error("rotation must be auto, portrait, landscape, reverse_portrait or reverse_landscape")
        }
        return "Rotation requested: ${value.lowercase()} (display now ${rotationName()}; may take a moment)."
    }

    fun setDarkMode(value: String): String {
        val mode = when (value.lowercase()) {
            "on" -> "yes"
            "off" -> "no"
            "auto" -> "auto"
            else -> error("dark_mode must be on, off or auto")
        }
        requireSecureWrite()
        val ok = Settings.Secure.putString(context.contentResolver, "ui_night_mode", mode)
        if (!ok) error("The system rejected the dark mode change.")
        val readback = secureNightMode() ?: "unset"
        return "Dark mode requested: ${value.lowercase()} (secure=$readback, app config=${darkMode()}). " +
            "Some Android versions need 'adb shell cmd uimode night ${if (mode == "yes") "yes" else "no"}' " +
            "or a configuration change before the UI follows."
    }

    fun setFontScale(value: Float): String {
        requireSystemWrite()
        val scale = value.coerceIn(0.85f, 2.0f)
        Settings.System.putFloat(context.contentResolver, Settings.System.FONT_SCALE, scale)
        return "Font scale set to ${"%.2f".format(scale)}."
    }

    fun setAnimations(enabled: Boolean): String {
        val scale = if (enabled) 1.0f else 0.0f
        requireSecureWrite()
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale
        )
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            scale
        )
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.WINDOW_ANIMATION_SCALE,
            scale
        )
        return "Animations ${if (enabled) "enabled" else "disabled"}."
    }

    fun setVolume(percent: Int): String {
        val audio = audioManager() ?: error("AudioManager is unavailable")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (percent.coerceIn(0, 100) / 100f * max).roundToInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        return "Media volume set to ${percent.coerceIn(0, 100)}% ($value/$max)."
    }

    @Suppress("DEPRECATION")
    fun setKeepAwake(enabled: Boolean): String {
        if (enabled) {
            if (wakeLock?.isHeld != true) {
                val power = context.getSystemService(PowerManager::class.java)
                    ?: error("PowerManager is unavailable")
                wakeLock = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Tester:QA"
                ).apply { acquire(30L * 60L * 1000L) }
            }
            return "Keep awake enabled."
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        return "Keep awake disabled."
    }

    fun restore(): String {
        val snapshot = saved ?: return "No device-state snapshot is available."
        val results = mutableListOf<String>()
        runCatching {
            if (snapshot.autoRotate == 1) {
                requireSystemWrite()
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    1
                )
            } else {
                requireSystemWrite()
                setFixedRotation(snapshot.userRotation)
            }
        }.onSuccess { results += "rotation" }.onFailure { results += "rotation failed: ${it.message}" }
        runCatching {
            requireSystemWrite()
            Settings.System.putFloat(
                context.contentResolver,
                Settings.System.FONT_SCALE,
                snapshot.fontScale
            )
        }.onSuccess { results += "font_scale" }.onFailure { results += "font failed: ${it.message}" }
        snapshot.nightMode?.let { mode ->
            runCatching {
                requireSecureWrite()
                Settings.Secure.putString(context.contentResolver, "ui_night_mode", mode)
            }.onSuccess { results += "dark_mode" }.onFailure { results += "dark mode failed: ${it.message}" }
        }
        runCatching {
            requireSecureWrite()
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                snapshot.animatorScale
            )
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                snapshot.transitionScale
            )
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                snapshot.windowScale
            )
        }.onSuccess { results += "animations" }.onFailure { results += "animations failed: ${it.message}" }
        runCatching { audioManager()?.setStreamVolume(AudioManager.STREAM_MUSIC, snapshot.volume, 0) }
            .onSuccess { results += "volume" }.onFailure { results += "volume failed: ${it.message}" }
        runCatching { setKeepAwake(snapshot.keepAwake) }
            .onSuccess { results += "keep_awake" }.onFailure { results += "wake lock failed: ${it.message}" }
        val failed = results.any { it.contains("failed") }
        // Keep the snapshot when something failed so restore can be retried after granting access.
        if (!failed) saved = null
        return "Restored: ${results.joinToString(", ")}." +
            if (failed) " Snapshot kept; fix the failures and call device_state action=restore again." else ""
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requireSecureWrite() {
        if (!canWriteSecureSettings()) {
            error(
                "WRITE_SECURE_SETTINGS is not granted (needed for dark mode and animations). Run once: " +
                    "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
        }
    }

    private fun requireSystemWrite() {
        if (!Settings.System.canWrite(context)) {
            error(
                "WRITE_SETTINGS is not granted. Enable 'Modify system settings' for Tester " +
                    "in Android settings, or run: adb shell appops set ${context.packageName} " +
                    "android:write_settings allow"
            )
        }
    }

    private fun setFixedRotation(value: Int) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        )
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.USER_ROTATION,
            value.coerceIn(0, 3)
        )
    }

    private fun rotation(): Int = runCatching {
        val manager = context.getSystemService(WindowManager::class.java)
        manager.defaultDisplay.rotation
    }.getOrDefault(0)

    private fun rotationName(): String = when (rotation()) {
        Surface.ROTATION_90 -> "landscape"
        Surface.ROTATION_180 -> "reverse_portrait"
        Surface.ROTATION_270 -> "reverse_landscape"
        else -> "portrait"
    }

    private fun secureNightMode(): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, "ui_night_mode")
    }.getOrNull()

    private fun darkMode(): String {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when (mode) {
            Configuration.UI_MODE_NIGHT_YES -> "on"
            Configuration.UI_MODE_NIGHT_NO -> "off"
            else -> "undefined"
        }
    }

    private fun animationsEnabled(): Boolean {
        val animator = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        val transition = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1.0f
        )
        val window = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.WINDOW_ANIMATION_SCALE,
            1.0f
        )
        return animator > 0f || transition > 0f || window > 0f
    }

    private fun screenOn(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun audioManager(): AudioManager? = context.getSystemService(AudioManager::class.java)
}
