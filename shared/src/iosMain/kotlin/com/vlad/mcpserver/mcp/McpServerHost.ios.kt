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
): McpServerHost {
    val env = platform.Foundation.NSProcessInfo.processInfo.environment
    val confirm = if (env["MCPSERVER_CONFIRM_TOOLS"] as? String == "0") {
        { false }
    } else {
        shouldConfirmTools
    }
    val host = IosMcpServerHost(getToken, confirm)
    val autostart = env["MCPSERVER_AUTOSTART"] as? String
    if (autostart == "1") host.start()
    return host
}

/**
 * iOS has no server implementation yet. This fallback keeps the shared
 * ViewModel compiling; the generated token is only used by the UI.
 */
actual fun newSecureToken(): String {
    val override = platform.Foundation.NSProcessInfo.processInfo.environment["MCPSERVER_TOKEN"] as? String
    if (!override.isNullOrBlank()) return override
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val random = kotlin.random.Random.Default
    return (1..43).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}
