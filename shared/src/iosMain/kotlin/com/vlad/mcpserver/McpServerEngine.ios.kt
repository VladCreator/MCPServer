package com.vlad.mcpserver

/**
 * iOS actual implementation of McpServerEngine (no-op for v0.0.1).
 */
actual class McpServerEngine actual constructor(
    private val getToken: () -> String,
    private val isSslEnabled: () -> Boolean,
    private val onClientPending: (String, String) -> Unit,
    private val onClientAuthorized: (String, String) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onToolExecuted: (String, String, String) -> Unit
) {
    actual fun start() {
        // No-op for iOS
    }

    actual fun stop() {
        // No-op for iOS
    }
}
