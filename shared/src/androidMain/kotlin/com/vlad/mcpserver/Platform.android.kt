package com.vlad.mcpserver

import android.content.Context
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

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

/**
 * Provides the local IPv4 address of the Android device.
 */
actual fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "127.0.0.1"
}
