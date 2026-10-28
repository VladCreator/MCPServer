package com.vlad.mcpserver

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.posix.time
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val isIos: Boolean = true
}

actual fun getPlatform(): Platform = IOSPlatform()

/**
 * Provides the absolute database file path on iOS.
 */
actual fun getDatabasePath(dbName: String): String {
    @OptIn(ExperimentalForeignApi::class)
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    )
    return "${documentDirectory?.path}/${dbName}"
}

/**
 * Provides the local IP address on iOS.
 */
actual fun getLocalIpAddress(): String = "127.0.0.1"


@OptIn(ExperimentalForeignApi::class)
actual fun nowMillis(): Long = time(null) * 1000L


actual fun startServerForegroundService(port: Int, secure: Boolean) = Unit

actual fun stopServerForegroundService() = Unit

actual fun updateServerActivity(toolName: String, status: String, timestamp: Long) = Unit


actual fun openSystemAccessibilitySettings() = Unit

actual fun openOverlayPermissionSettings() = Unit

actual fun isOverlayPermissionGranted(): Boolean = false

actual fun openWriteSettingsPermission() = Unit

actual fun isWriteSettingsGranted(): Boolean = false

actual fun isTouchServiceEnabled(): Boolean = false
