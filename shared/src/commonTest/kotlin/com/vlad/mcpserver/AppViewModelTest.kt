package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.McpClient
import com.vlad.mcpserver.mcp.McpServerHost
import com.vlad.mcpserver.mcp.ServerEvent
import com.vlad.mcpserver.mcp.ServerStatus
import com.vlad.mcpserver.mcp.ToolApprovalRequest
import com.vlad.mcpserver.mcp.Transport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    @Test
    fun serverStartsAndStops() = runTest {
        val host = FakeMcpServerHost()
        val viewModel = viewModel(host)

        assertFalse(viewModel.isServerRunning)
        viewModel.toggleServer()
        assertTrue(viewModel.isServerRunning)
        assertEquals(1, host.startCount)

        viewModel.toggleServer()
        assertFalse(viewModel.isServerRunning)
        assertEquals(1, host.stopCount)
    }

    @Test
    fun pendingClientIsShownAndApproved() = runTest {
        val host = FakeMcpServerHost()
        val viewModel = viewModel(host)
        val client = client("c1")

        host.emit(ServerEvent.Pending(client))
        assertEquals(1, viewModel.pendingClients.value.size)
        assertEquals("Pending Approval", viewModel.pendingClients.value.first().status)
        assertEquals("192.168.1.20", viewModel.pendingClients.value.first().remoteAddress)

        viewModel.approveClient("c1")
        assertEquals(listOf("c1"), host.approved)
        assertEquals(0, viewModel.pendingClients.value.size)

        host.emit(ServerEvent.Authorized(client))
        assertEquals(1, viewModel.connectedClients.value.size)
        assertEquals("Authenticated", viewModel.connectedClients.value.first().status)

        host.emit(ServerEvent.Disconnected("c1"))
        assertEquals(0, viewModel.connectedClients.value.size)
    }

    @Test
    fun pendingClientCanBeRejected() = runTest {
        val host = FakeMcpServerHost()
        val viewModel = viewModel(host)
        val client = client("c2")

        host.emit(ServerEvent.Pending(client))
        viewModel.rejectClient("c2")

        assertEquals(listOf("c2"), host.rejected)
        assertEquals(0, viewModel.pendingClients.value.size)
        assertEquals(0, viewModel.connectedClients.value.size)
    }

    @Test
    fun tokenCanBeUpdated() = runTest {
        val viewModel = viewModel(FakeMcpServerHost())
        val custom = "custom_secure_ai_token_999"
        viewModel.updateBearerToken(custom)
        assertEquals(custom, viewModel.bearerToken)
    }

    @Test
    fun secureTokenIsGenerated() = runTest {
        val viewModel = viewModel(FakeMcpServerHost())
        val token = viewModel.generateSecureToken()
        assertTrue(token.length >= 40, "Expected a long URL-safe token, got ${token.length} chars")
        assertEquals(token, viewModel.bearerToken)
    }

    @Test
    fun emptyTokenIsReported() = runTest {
        val viewModel = viewModel(FakeMcpServerHost())
        viewModel.updateBearerToken("")
        assertTrue(viewModel.tokenWarning != null)
    }

    @Test
    fun sslToggleStopsRunningServer() = runTest {
        val host = FakeMcpServerHost()
        val viewModel = viewModel(host)
        assertFalse(viewModel.sslEnabled)

        viewModel.startServer()
        assertTrue(viewModel.isServerRunning)

        viewModel.updateSslEnabled(true)
        assertTrue(viewModel.sslEnabled)
        assertEquals(1, host.stopCount)
        assertFalse(viewModel.isServerRunning)
        assertTrue(viewModel.connectionUrl.startsWith("https://"))
        assertTrue(viewModel.connectionUrl.endsWith(":8443/mcp"))
    }

    @Test
    fun darkModeCanBeToggled() = runTest {
        val viewModel = viewModel(FakeMcpServerHost())
        assertTrue(viewModel.isDarkMode)
        viewModel.updateDarkMode(false)
        assertFalse(viewModel.isDarkMode)
        viewModel.updateDarkMode(true)
        assertTrue(viewModel.isDarkMode)
    }

    @Test
    fun toolApprovalRequestCanBeApproved() = runTest {
        val host = FakeMcpServerHost()
        val viewModel = viewModel(host)
        val request = ToolApprovalRequest(
            id = "approval-1",
            clientId = "c1",
            clientName = "Test Agent",
            toolName = "take_photo",
            arguments = "{}",
            requestedAt = 1_700_000_000_000L
        )

        host.emit(ServerEvent.ToolApprovalRequested(request))
        assertEquals(1, viewModel.toolApprovalRequests.value.size)

        viewModel.resolveToolApproval("approval-1", true)

        assertEquals(0, viewModel.toolApprovalRequests.value.size)
        assertEquals(listOf("approval-1" to true), host.resolvedToolApprovals)
    }

    @Test
    fun toolApprovalRequestIsRemovedWhenResolved() = runTest {
        val host = FakeMcpServerHost()
        val viewModel = viewModel(host)
        val request = ToolApprovalRequest(
            id = "approval-2",
            clientId = "c1",
            clientName = "Test Agent",
            toolName = "read_file",
            arguments = """{"path":"/tmp/x"}""",
            requestedAt = 1_700_000_000_000L
        )

        host.emit(ServerEvent.ToolApprovalRequested(request))
        assertEquals(1, viewModel.toolApprovalRequests.value.size)

        host.emit(ServerEvent.ToolApprovalResolved("approval-2", false))
        assertEquals(0, viewModel.toolApprovalRequests.value.size)
    }

    @Test
    fun sensitiveToolConfirmationCanBeDisabled() = runTest {
        val viewModel = viewModel(FakeMcpServerHost())
        assertTrue(viewModel.confirmSensitiveTools)
        viewModel.updateConfirmSensitiveTools(false)
        assertFalse(viewModel.confirmSensitiveTools)
    }

    @Test
    fun failedStatusIsExposed() = runTest {
        val host = FakeMcpServerHost()
        val viewModel = viewModel(host)
        host.fail("Address already in use")

        val status = viewModel.serverStatus.value
        assertIs<ServerStatus.Failed>(status)
        assertEquals("Address already in use", status.message)
        assertFalse(viewModel.isServerRunning)
    }

    @Test
    fun localIpAddressIsNotEmpty() {
        val ip = getLocalIpAddress()
        assertTrue(ip.isNotEmpty())
    }

    private fun viewModel(host: FakeMcpServerHost): AppViewModel =
        AppViewModel(
            hostFactory = { _, _, _ -> host },
            databasePathProvider = { null },
            dispatcher = UnconfinedTestDispatcher(),
            uiDispatcher = UnconfinedTestDispatcher()
        )

    private fun client(id: String): McpClient =
        McpClient(
            id = id,
            name = "Test Agent",
            remoteAddress = "192.168.1.20",
            transport = Transport.STREAMABLE_HTTP,
            connectedAt = 1_700_000_000_000L
        )

    private class FakeMcpServerHost : McpServerHost {
        private val statusState = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        private val eventFlow = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 32)

        override val status: StateFlow<ServerStatus> = statusState.asStateFlow()
        override val events: SharedFlow<ServerEvent> = eventFlow.asSharedFlow()

        var startCount = 0
        var stopCount = 0
        val approved = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val resolvedToolApprovals = mutableListOf<Pair<String, Boolean>>()

        override fun start() {
            startCount++
            statusState.value = ServerStatus.Running(port = 8080, secure = false)
        }

        override fun stop() {
            stopCount++
            statusState.value = ServerStatus.Stopped
        }

        override fun approve(clientId: String) {
            approved += clientId
        }

        override fun reject(clientId: String) {
            rejected += clientId
        }

        override fun resolveToolApproval(requestId: String, approved: Boolean) {
            resolvedToolApprovals += requestId to approved
        }

        override fun close() {
            statusState.value = ServerStatus.Stopped
        }

        suspend fun emit(event: ServerEvent) {
            eventFlow.emit(event)
        }

        fun fail(message: String) {
            statusState.value = ServerStatus.Failed(message)
        }
    }
}
