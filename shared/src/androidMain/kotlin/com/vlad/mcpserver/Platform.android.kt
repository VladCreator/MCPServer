package com.vlad.mcpserver

import android.content.Context
import android.os.Build

/**
 * Global application context reference for Android platform services.
 */
lateinit var appContext: Context

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

/**
 * Provides the absolute database file path on Android.
 */
actual fun getDatabasePath(dbName: String): String {
    return appContext.getDatabasePath(dbName).absolutePath
}
