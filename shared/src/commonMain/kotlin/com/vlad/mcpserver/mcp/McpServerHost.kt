package com.vlad.mcpserver.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform server. The Android implementation runs an embedded Ktor server;
 * other platforms return an unsupported host for now.
 */
interface McpServerHost {
    val status: StateFlow<ServerStatus>
    val events: SharedFlow<ServerEvent>

    fun start()
    fun stop()
    fun approve(clientId: String)
    fun reject(clientId: String)
    fun resolveToolApproval(requestId: String, approved: Boolean)
    fun close()
}

expect fun createMcpServerHost(
    getToken: () -> String,
    isSslEnabled: () -> Boolean,
    shouldConfirmTools: () -> Boolean
): McpServerHost

/**
 * Returns a cryptographically strong token on platforms that support it.
 */
expect fun newSecureToken(): String

/**
 * Host used on platforms where the embedded server is not implemented.
 */
class UnsupportedMcpServerHost : McpServerHost {
    private val statusState = MutableStateFlow<ServerStatus>(ServerStatus.Unsupported)
    private val eventFlow = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 8)

    override val status: StateFlow<ServerStatus> = statusState.asStateFlow()
    override val events: SharedFlow<ServerEvent> = eventFlow.asSharedFlow()

    override fun start() {
        statusState.value = ServerStatus.Unsupported
    }

    override fun stop() {
        statusState.value = ServerStatus.Unsupported
    }

    override fun approve(clientId: String) = Unit

    override fun reject(clientId: String) = Unit

    override fun resolveToolApproval(requestId: String, approved: Boolean) = Unit

    override fun close() = Unit
}
