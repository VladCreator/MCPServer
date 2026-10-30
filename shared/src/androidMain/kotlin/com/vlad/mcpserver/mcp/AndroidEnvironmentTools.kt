package com.vlad.mcpserver.mcp

import android.app.LocaleManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import com.vlad.mcpserver.AppVisibility
import com.vlad.mcpserver.McpTouchAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

internal class AndroidEnvironmentTools(private val context: Context) {
    private var clipboardMarker: String? = null
    private var clipboardClient: String? = null
    private var clipboardDigest: String? = null

    suspend fun openLink(arguments: JsonObject): ToolResult {
        val pkg = packageName(arguments)
        val raw = arguments.string("uri") ?: return failure("invalid_uri", "uri is required.")
        try { AppLink.validate(raw) } catch (error: IllegalArgumentException) {
            return failure("invalid_uri", error.message ?: "Invalid app link.")
        }
        val uri = Uri.parse(raw)
        val wait = if ("wait_ms" in arguments) {
            val value = arguments["wait_ms"] as? JsonPrimitive
            require(value != null && !value.isString && value.intOrNull != null) { "wait_ms must be an integer." }
            value.int
        } else 5000
        require(wait in 250..30_000) { "wait_ms must be 250-30000." }
        val service = McpTouchAccessibilityService.instance()
            ?: return failure("accessibility_required", "Enable accessibility to verify target readiness before launching.")
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val candidates = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }.filter { it.exported && it.enabled && it.packageName == pkg && it.permission == null }
        if (candidates.size != 1) return failure("ambiguous_or_unavailable_handler",
            "The explicit target must expose exactly one enabled, exported, permission-free browsable handler.")
        intent.setClassName(pkg, candidates.single().name)
        try {
            withContext(Dispatchers.Main) { context.startActivity(intent) }
        } catch (error: SecurityException) {
            android.util.Log.w("TesterEnvironment", "App link launch denied")
            return failure("launch_denied", "Android rejected the app-targeted launch.")
        } catch (error: android.content.ActivityNotFoundException) {
            return failure("handler_unavailable", "The resolved handler became unavailable.")
        }
        val start = android.os.SystemClock.elapsedRealtime()
        var generation = service.windowGeneration.get()
        var quietSince = start
        do {
            val now = android.os.SystemClock.elapsedRealtime()
            val currentGeneration = service.windowGeneration.get()
            if (currentGeneration != generation) {
                generation = currentGeneration
                quietSince = now
            }
            if (service.rootInActiveWindow?.packageName?.toString() == pkg &&
                now - quietSince >= 300) return outcome(buildJsonObject {
                put("reason", "ready"); put("package_name", pkg); put("elapsed_ms", now - start)
                put("verification", "foreground_and_accessibility_quiet; not proof of deep-link business outcome")
            })
            delay(100)
        } while (android.os.SystemClock.elapsedRealtime() - start < wait)
        return failure("readiness_timeout", "Launch dispatched, but target did not become foreground/quiet. Do not retry blindly.")
    }

    suspend fun clipboard(clientId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        if (!AppVisibility.isForeground) return@withContext failure("foreground_required",
            "Tester must be foreground for clipboard access; no background reads or automatic app switches.")
        val manager = context.getSystemService(ClipboardManager::class.java)
        when (arguments.string("action")) {
            "write" -> {
                val text = arguments.string("text") ?: return@withContext failure("invalid_text", "text is required.")
                require(text.length <= 5000) { "Clipboard text exceeds 5000 characters." }
                val marker = UUID.randomUUID().toString()
                val clip = ClipData.newPlainText("Tester synthetic test data", text).apply {
                    description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        putString(CLIP_OWNER, marker)
                    }
                }
                manager.setPrimaryClip(clip)
                clipboardMarker = marker
                clipboardClient = clientId
                clipboardDigest = RunStore.hash(text)
                if (ownedClipboardText(manager) != text) return@withContext failure("clipboard_write_unverified",
                    "Clipboard write could not be verified; unrelated clipboard data will not be read.")
                outcome(buildJsonObject { put("reason", "written"); put("length", text.length); put("previous_content_saved", false) })
            }
            "read_owned", "clear_owned" -> {
                if (clipboardClient != clientId) return@withContext failure("clipboard_not_owned",
                    "Clipboard was not written by this Tester process or has been replaced; nothing read or cleared.")
                val text = ownedClipboardText(manager)
                    ?: return@withContext failure("clipboard_not_owned", "Clipboard changed or unavailable; nothing returned or cleared.")
                if (arguments.string("action") == "clear_owned") {
                    manager.clearPrimaryClip()
                    clipboardMarker = null
                    clipboardClient = null
                    clipboardDigest = null
                    outcome(buildJsonObject { put("reason", "cleared") })
                } else {
                    outcome(buildJsonObject { put("reason", "owned_text"); put("text", text) })
                }
            }
            else -> failure("invalid_action", "action must be write, read_owned or clear_owned.")
        }
    }

    fun inspect(arguments: JsonObject): ToolResult {
        val pkg = packageName(arguments)
        val info = try { context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }
        catch (error: PackageManager.NameNotFoundException) {
            return failure("package_unavailable", "Target is not installed or visible.")
        }
        val locale = try {
            buildJsonObject {
                put("status", "available")
                put("app_locale_override", context.getSystemService(LocaleManager::class.java).getApplicationLocales(pkg).toLanguageTags())
                put("effective_target_locale", "unknown; empty override does not prove target resource selection")
            }
        } catch (error: SecurityException) {
            buildJsonObject { put("status", "denied"); put("reason", "READ_APP_SPECIFIC_LOCALES_or_target_cooperation_required") }
        }
        return outcome(buildJsonObject {
            put("package_name", pkg)
            putJsonArray("permissions") {
                info.requestedPermissions.orEmpty().take(200).forEachIndexed { index, permission ->
                    add(buildJsonObject {
                        put("permission", permission)
                        put("granted", (info.requestedPermissionsFlags?.getOrNull(index) ?: 0) and
                            android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
                    })
                }
            }
            put("permissions_truncated", info.requestedPermissions.orEmpty().size > 200)
            put("permission_scope", "package flags only; does not establish app-ops, one-time or device-policy access")
            put("locale", locale)
            put("system_locales", android.content.res.Resources.getSystem().configuration.locales.toLanguageTags())
            putJsonObject("unsupported_controls") {
                put("permission_mutation", "Requires explicitly authorized host/managed-device adapter; none configured")
                put("locale_mutation", "Requires target-app hook or authorized privileged adapter; none configured")
                put("location", "Requires developer-provisioned mock-location adapter; none configured")
                put("app_data_reset", "Destructive host/managed allowlisted adapter not configured; no native reset")
                put("device_time", "Privileged system mutation deferred; app test-clock hook not configured")
                put("launch_arguments", "Arbitrary extras/intents unsupported; use explicit open_link")
            }
        })
    }

    private fun ownedClipboardText(manager: ClipboardManager): String? {
        if (clipboardMarker == null ||
            manager.primaryClipDescription?.extras?.getString(CLIP_OWNER) != clipboardMarker) return null
        val clip = manager.primaryClip ?: return null
        // Never coerce URIs into file/provider reads, including during clear validation.
        if (clip.description.extras?.getString(CLIP_OWNER) != clipboardMarker || clip.itemCount != 1 ||
            clip.getItemAt(0).uri != null || clip.getItemAt(0).intent != null) return null
        val text = clip.getItemAt(0).text?.toString() ?: return null
        return text.takeIf { it.length <= 5000 && RunStore.hash(it) == clipboardDigest }
    }

    private fun packageName(arguments: JsonObject): String {
        val pkg = arguments.string("package_name")
        require(pkg != null && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg)) {
            "A valid package_name is required."
        }
        return pkg
    }

    private fun outcome(data: JsonObject) = ToolResult(data.toString(), structuredContent = data)
    private fun failure(reason: String, message: String): ToolResult = ToolResult(message, isError = true,
        structuredContent = buildJsonObject { put("reason", reason); put("retryable", false) })
    companion object { private const val CLIP_OWNER = "com.vlad.tester.clip_owner" }
}
