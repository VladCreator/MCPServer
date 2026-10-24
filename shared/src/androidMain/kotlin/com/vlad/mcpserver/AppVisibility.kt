package com.vlad.mcpserver

/**
 * Tracks whether the Activity is visible. Camera capture is only allowed while
 * the app is in the foreground; Android restricts background camera access
 * without a dedicated camera foreground service.
 */
object AppVisibility {
    @Volatile
    var isForeground: Boolean = false
}
