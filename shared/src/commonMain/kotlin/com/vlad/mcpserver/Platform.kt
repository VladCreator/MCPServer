package com.vlad.mcpserver

interface Platform {
    val name: String

    /** True on iPhone and iPad; do not infer this from [name] ("iPadOS" on iPad). */
    val isIos: Boolean
}

expect fun getPlatform(): Platform

/**
 * Returns the absolute database file path for the specific platform.
 */
expect fun getDatabasePath(dbName: String): String

/**
 * Returns the local IP address of the device on the active network interface.
 */
expect fun getLocalIpAddress(): String

/**
 * Current wall-clock time in milliseconds since the Unix epoch.
 */
expect fun nowMillis(): Long


/**
 * Starts a foreground service that keeps the process alive while the MCP
 * server is running. No-op on platforms without Android services.
 */
expect fun startServerForegroundService(port: Int, secure: Boolean)

/**
 * Stops the foreground keep-alive service.
 */
expect fun stopServerForegroundService()

/**
 * Shows the latest activity in the running server notification. On Android 16+
 * it is posted as a Live Update, so it appears as a status bar chip (capsule).
 */
expect fun updateServerActivity(toolName: String, status: String, timestamp: Long)


/**
 * Opens the system accessibility settings so the user can enable the touch
 * injection service.
 */
expect fun openSystemAccessibilitySettings()

/**
 * Opens the "Display over other apps" settings page so the user can let the
 * launch_app tool start activities while Tester is in the background.
 */
expect fun openOverlayPermissionSettings()

/** Whether the "Display over other apps" permission is granted. */
expect fun isOverlayPermissionGranted(): Boolean

/** Opens the special "Modify system settings" access page for WRITE_SETTINGS. */
expect fun openWriteSettingsPermission()

/** Whether the app can write Settings.System (rotation, font scale). */
expect fun isWriteSettingsGranted(): Boolean

/** Whether the MCP Touch accessibility service is enabled and connected. */
expect fun isTouchServiceEnabled(): Boolean
