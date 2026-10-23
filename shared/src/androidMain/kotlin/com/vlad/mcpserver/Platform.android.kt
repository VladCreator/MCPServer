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
 * Provides the local IPv4 network address of the active Wi-Fi/Ethernet interface.
 */
actual fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        var candidateIp: String? = null

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            
            // Ignore down or loopback interfaces
            if (!networkInterface.isUp || networkInterface.isLoopback) continue

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val hostAddress = addr.hostAddress ?: continue
                    
                    // Prioritize Wi-Fi (wlan), Ethernet (eth), or USB tethering (rndis)
                    val interfaceName = networkInterface.name.lowercase()
                    if (interfaceName.contains("wlan") || interfaceName.contains("eth") || interfaceName.contains("rndis")) {
                        return hostAddress
                    } else if (candidateIp == null) {
                        candidateIp = hostAddress
                    }
                }
            }
        }
        if (candidateIp != null) return candidateIp
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "127.0.0.1"
}
