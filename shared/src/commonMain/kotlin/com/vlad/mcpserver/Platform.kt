package com.vlad.mcpserver

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * Returns the absolute database file path for the specific platform.
 */
expect fun getDatabasePath(dbName: String): String
