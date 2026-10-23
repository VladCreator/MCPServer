package com.vlad.mcpserver

/**
 * Expect class for the embedded Ktor server engine across platforms.
 */
expect class McpServerEngine(
    getToken: () -> String,
    isSslEnabled: () -> Boolean,
    onClientPending: (String, String) -> Unit,
    onClientAuthorized: (String, String) -> Unit,
    onClientDisconnected: (String) -> Unit,
    onToolExecuted: (String, String, String) -> Unit
) {
    /**
     * Starts the embedded server.
     */
    fun start()

    /**
     * Stops the embedded server.
     */
    fun stop()
}
