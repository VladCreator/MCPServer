package com.vlad.mcpserver.mcp

/**
 * iOS host: the embedded server is Android-only for now. Returning an
 * unsupported host lets the shared UI show an explicit state instead of
 * pretending the server is running.
 */
actual fun createMcpServerHost(
    getToken: () -> String,
    isSslEnabled: () -> Boolean,
    shouldConfirmTools: () -> Boolean
): McpServerHost = IosMcpServerHost(getToken, shouldConfirmTools)

/**
 * iOS has no server implementation yet. This fallback keeps the shared
 * ViewModel compiling; the generated token is only used by the UI.
 */
actual fun newSecureToken(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val random = kotlin.random.Random.Default
    return (1..43).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}
