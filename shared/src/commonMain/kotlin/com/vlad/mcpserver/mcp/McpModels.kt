package com.vlad.mcpserver.mcp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lifecycle state of the embedded MCP server.
 */
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Starting : ServerStatus
    data class Running(
        val port: Int,
        val secure: Boolean,
        val fingerprint: String? = null
    ) : ServerStatus

    data class Failed(val message: String) : ServerStatus
    data object Unsupported : ServerStatus
}

enum class Transport {
    STREAMABLE_HTTP,
    SSE,
    WEBSOCKET
}

/**
 * Immutable description of a connected or pending MCP client.
 */
data class McpClient(
    val id: String,
    val name: String,
    val remoteAddress: String,
    val transport: Transport,
    val connectedAt: Long
)

enum class ToolCallOutcome {
    SUCCESS,
    FAILED
}

data class ToolCallRecord(
    val clientId: String,
    val toolName: String,
    val arguments: String,
    val outcome: ToolCallOutcome,
    val message: String,
    val timestamp: Long
)

/**
 * A sensitive tool call waiting for explicit user approval.
 */
data class ToolApprovalRequest(
    val id: String,
    val clientId: String,
    val clientName: String,
    val toolName: String,
    val arguments: String,
    val requestedAt: Long
)

/**
 * Events emitted by the platform server host and consumed by the ViewModel.
 */
sealed interface ServerEvent {
    data class Pending(val client: McpClient) : ServerEvent
    data class Authorized(val client: McpClient) : ServerEvent
    data class Disconnected(val clientId: String) : ServerEvent
    data class ToolCalled(val record: ToolCallRecord) : ServerEvent
    data class ToolApprovalRequested(val request: ToolApprovalRequest) : ServerEvent
    data class ToolApprovalResolved(val requestId: String, val approved: Boolean) : ServerEvent
}

/**
 * A single MCP transport session. A session owns the approval decision and the
 * outbox used by server-push transports (SSE and Streamable HTTP GET).
 */
class McpSession(
    val client: McpClient
) {
    val id: String get() = client.id

    val outbox = Channel<String>(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val authorizedState = MutableStateFlow(false)
    private val pendingNotified = MutableStateFlow(false)
    private val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()

    val authorized: StateFlow<Boolean> = authorizedState.asStateFlow()
    val isAuthorized: Boolean get() = authorizedState.value

    var initialized: Boolean = false
    var protocolVersion: String? = null

    /** Returns true only for the first successful authorization. */
    fun markAuthorized(): Boolean {
        val changed = authorizedState.compareAndSet(expect = false, update = true)
        if (changed) {
            decision.complete(true)
        }
        return changed
    }

    /** Returns true only the first time the pending event must be emitted. */
    fun markPendingOnce(): Boolean =
        pendingNotified.compareAndSet(expect = false, update = true)

    fun approve() {
        decision.complete(true)
    }

    fun reject() {
        decision.complete(false)
    }

    suspend fun awaitDecision(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { decision.await() } ?: false

    fun close() {
        outbox.close()
        if (!decision.isCompleted) {
            decision.complete(false)
        }
    }
}
