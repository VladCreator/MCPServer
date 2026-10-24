package com.vlad.mcpserver

interface Platform {
    val name: String
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
