package com.vlad.mcpserver

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
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
