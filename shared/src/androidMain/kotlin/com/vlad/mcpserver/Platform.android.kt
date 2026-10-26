package com.vlad.mcpserver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
fun isAppContextInitialized(): Boolean = ::appContext.isInitialized

actual fun getDatabasePath(dbName: String): String {
    val file = appContext.getDatabasePath(dbName)
    file.parentFile?.mkdirs()
    return file.absolutePath
}

/**
 * Returns every non-loopback IPv4 address of the device. Used by the TLS
 * certificate SAN and by the dashboard.
 */
fun getAllLocalIpv4Addresses(): List<String> {
    val addresses = linkedSetOf<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("127.0.0.1")
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val inetAddresses = networkInterface.inetAddresses
            while (inetAddresses.hasMoreElements()) {
                val address = inetAddresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    address.hostAddress?.let { addresses += it }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    if (addresses.isEmpty()) {
        addresses += getLocalIpAddress()
    }
    return addresses.toList()
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


actual fun nowMillis(): Long = System.currentTimeMillis()


actual fun startServerForegroundService(port: Int, secure: Boolean) {
    if (!::appContext.isInitialized) return
    runCatching {
        val intent = Intent(appContext, McpServerKeepAliveService::class.java).apply {
            putExtra(McpServerKeepAliveService.EXTRA_PORT, port)
            putExtra(McpServerKeepAliveService.EXTRA_SECURE, secure)
        }
        appContext.startForegroundService(intent)
    }
}

actual fun stopServerForegroundService() {
    if (!::appContext.isInitialized) return
    runCatching {
        appContext.stopService(Intent(appContext, McpServerKeepAliveService::class.java))
    }
}


actual fun openSystemAccessibilitySettings() {
    if (!::appContext.isInitialized) return
    runCatching {
        appContext.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

actual fun openOverlayPermissionSettings() {
    if (!::appContext.isInitialized) return
    runCatching {
        appContext.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

actual fun isOverlayPermissionGranted(): Boolean =
    ::appContext.isInitialized && Settings.canDrawOverlays(appContext)

actual fun isTouchServiceEnabled(): Boolean = McpTouchAccessibilityService.instance() != null
