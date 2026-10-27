package com.vlad.mcpserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.vlad.mcpserver.mcp.BearerAuth
import com.vlad.mcpserver.mcp.McpClient
import com.vlad.mcpserver.mcp.McpServerHost
import com.vlad.mcpserver.mcp.ServerEvent
import com.vlad.mcpserver.mcp.ServerStatus
import com.vlad.mcpserver.mcp.ToolApprovalRequest
import com.vlad.mcpserver.mcp.ToolCallOutcome
import com.vlad.mcpserver.mcp.createMcpServerHost
import com.vlad.mcpserver.mcp.newSecureToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the dashboard, settings and server lifecycle.
 *
 * Network and storage work happens in [scope], never on the caller (UI)
 * thread. Server events are reduced sequentially into immutable [StateFlow]
 * lists, so there is no read-modify-write race between transports.
 */
class AppViewModel(
    hostFactory: (() -> String, () -> Boolean, () -> Boolean) -> McpServerHost =
        { getToken, isSsl, confirmTools ->
            createMcpServerHost(getToken, isSsl, confirmTools)
        },
    private val databasePathProvider: () -> String? = {
        runCatching { getDatabasePath("mcp_server.db") }.getOrNull()
    },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    var isDarkMode by mutableStateOf(true)
        private set

    var showSettings by mutableStateOf(false)
        private set

    var bearerToken by mutableStateOf(newSecureToken())
        private set

    var sslEnabled by mutableStateOf(false)
        private set

    var confirmSensitiveTools by mutableStateOf(true)
        private set

    var storageError by mutableStateOf<String?>(null)
        private set

    private var localIpAddress by mutableStateOf("127.0.0.1")

    val serverName = "Tester"

    private val serverHost: McpServerHost = hostFactory(
        { bearerToken },
        { sslEnabled },
        { confirmSensitiveTools }
    )
    val serverStatus: StateFlow<ServerStatus> = serverHost.status

    private val connectedState = MutableStateFlow<List<AiClient>>(emptyList())
    val connectedClients: StateFlow<List<AiClient>> = connectedState.asStateFlow()

    private val pendingState = MutableStateFlow<List<AiClient>>(emptyList())
    val pendingClients: StateFlow<List<AiClient>> = pendingState.asStateFlow()

    private val executionLogsState = MutableStateFlow<List<ToolExecutionLog>>(emptyList())
    val executionLogs: StateFlow<List<ToolExecutionLog>> = executionLogsState.asStateFlow()

    private val toolApprovalsState = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    val toolApprovalRequests: StateFlow<List<ToolApprovalRequest>> = toolApprovalsState.asStateFlow()

    private var database: AppDatabase? = null
    private var tokenSaveJob: Job? = null

    val isServerRunning: Boolean
        get() = serverStatus.value is ServerStatus.Running

    val tokenWarning: String?
        get() = when {
            bearerToken.isBlank() -> "The token is empty: every client will be rejected."
            bearerToken.length < 16 -> "The token is short. Use at least 16 characters."
            else -> null
        }

    val connectionUrl: String
        get() = "${if (sslEnabled) "https" else "http"}://$localIpAddress:$port/mcp"

    val sseUrl: String
        get() = "${if (sslEnabled) "https" else "http"}://$localIpAddress:$port/sse"

    val wsUrl: String
        get() = "${if (sslEnabled) "wss" else "ws"}://$localIpAddress:$port/ws"

    val usbUrl: String
        get() = "${if (sslEnabled) "https" else "http"}://localhost:$port/mcp"

    val adbForwardCommand: String
        get() = "adb forward tcp:$port tcp:$port"

    private val port: Int
        get() = (serverStatus.value as? ServerStatus.Running)?.port
            ?: if (sslEnabled) HTTPS_PORT else HTTP_PORT

    init {
        localIpAddress = runCatching { getLocalIpAddress() }.getOrDefault("127.0.0.1")
        scope.launch {
            serverHost.events.collect { event -> handleEvent(event) }
        }
        scope.launch {
            serverStatus.collect { status ->
                when (status) {
                    is ServerStatus.Running -> startServerForegroundService(status.port, status.secure)
                    is ServerStatus.Stopped, is ServerStatus.Failed, is ServerStatus.Unsupported ->
                        stopServerForegroundService()
                    is ServerStatus.Starting -> Unit
                }
            }
        }
        scope.launch { loadStorage() }
    }

    fun toggleSettings() {
        showSettings = !showSettings
    }

    fun updateBearerToken(newToken: String) {
        val cleaned = BearerAuth.sanitizeToken(newToken)
        bearerToken = cleaned
        tokenSaveJob?.cancel()
        tokenSaveJob = scope.launch {
            delay(500)
            persistToken(cleaned)
        }
    }

    fun generateSecureToken(): String {
        val token = newSecureToken()
        updateBearerToken(token)
        return token
    }

    fun updateSslEnabled(enabled: Boolean) {
        if (sslEnabled == enabled) return
        sslEnabled = enabled
        scope.launch { database?.saveSetting("ssl_enabled", enabled.toString()) }
        if (serverStatus.value is ServerStatus.Running || serverStatus.value is ServerStatus.Starting) {
            // The port and the protocol change; the user must start again.
            serverHost.stop()
        }
    }

    fun updateDarkMode(enabled: Boolean) {
        isDarkMode = enabled
        scope.launch { database?.saveSetting("dark_mode", enabled.toString()) }
    }

    fun updateConfirmSensitiveTools(enabled: Boolean) {
        confirmSensitiveTools = enabled
        scope.launch { database?.saveSetting("confirm_tools", enabled.toString()) }
    }

    fun resolveToolApproval(requestId: String, approved: Boolean) {
        val request = toolApprovalsState.value.firstOrNull { it.id == requestId }
        toolApprovalsState.update { list -> list.filterNot { it.id == requestId } }
        serverHost.resolveToolApproval(requestId, approved)
        if (request != null) {
            scope.launch {
                logEvent(
                    request.clientId,
                    "ToolApproval",
                    request.toolName,
                    if (approved) "SUCCESS" else "FAILED",
                    if (approved) "User approved the tool call." else "User rejected the tool call."
                )
            }
        }
    }

    fun toggleServer() {
        when (serverStatus.value) {
            is ServerStatus.Running, is ServerStatus.Starting -> stopServer()
            else -> startServer()
        }
    }

    fun startServer() {
        localIpAddress = runCatching { getLocalIpAddress() }.getOrDefault(localIpAddress)
        serverHost.start()
    }

    fun stopServer() {
        serverHost.stop()
    }

    fun openTouchAccessibilitySettings() {
        openSystemAccessibilitySettings()
    }

    fun openOverlaySettings() {
        openOverlayPermissionSettings()
    }

    fun openWriteSettings() {
        openWriteSettingsPermission()
    }

    /**
     * Approves a pending session. The transport that is waiting for the
     * decision resumes and emits [ServerEvent.Authorized].
     */
    fun approveClient(clientId: String) {
        pendingState.update { list -> list.filterNot { it.id == clientId } }
        serverHost.approve(clientId)
    }

    /**
     * Rejects a pending session and closes the transport.
     */
    fun rejectClient(clientId: String) {
        pendingState.update { list -> list.filterNot { it.id == clientId } }
        serverHost.reject(clientId)
        scope.launch {
            logEvent(clientId, "ManualReject", "User rejected client connection", "FAILED", "Connection denied.")
        }
    }

    private suspend fun loadStorage() {
        val path = databasePathProvider()
        if (path == null) return
        val opened = AppDatabase(path)
        try {
            opened.init()
        } catch (error: Throwable) {
            opened.close()
            val message = "Storage unavailable: ${error.message ?: error::class.simpleName}"
            withContext(uiDispatcher) { storageError = message }
            return
        }
        database = opened

        val rawToken = opened.getSetting("bearer_token")
        val storedToken = decodeToken(rawToken)
            ?.takeIf { it.isNotBlank() }
            ?.let { BearerAuth.sanitizeToken(it) }
        val needsMigration = rawToken != null && !SecretCodec.isEncrypted(rawToken) && storedToken != null
        val storedSsl = opened.getSetting("ssl_enabled")?.toBooleanStrictOrNull()
        val storedDark = opened.getSetting("dark_mode")?.toBooleanStrictOrNull()
        val storedConfirmTools = opened.getSetting("confirm_tools")?.toBooleanStrictOrNull()
        val tokenToPersist = if (storedToken == null) bearerToken else null
        val logs = opened.getCommandHistories(limit = MAX_VISIBLE_LOGS)

        withContext(uiDispatcher) {
            if (storedToken != null) bearerToken = storedToken
            storedSsl?.let { sslEnabled = it }
            storedDark?.let { isDarkMode = it }
            storedConfirmTools?.let { confirmSensitiveTools = it }
            executionLogsState.update { current ->
                (current + logs.filter { stored -> current.none { it.id == stored.id } })
                    .take(MAX_VISIBLE_LOGS)
            }
        }

        if (needsMigration) {
            storedToken?.let { persistToken(it) }
        }
        if (tokenToPersist != null) {
            // First run (or an unreadable keystore entry): persist the current token.
            persistToken(tokenToPersist)
        }
    }

    private fun decodeToken(raw: String?): String? {
        if (raw == null) return null
        return if (SecretCodec.isEncrypted(raw)) decryptSecret(raw) else raw
    }

    private suspend fun persistToken(token: String) {
        val encoded = encryptSecret(token) ?: token
        database?.saveSetting("bearer_token", encoded)
    }

    private suspend fun handleEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Pending -> {
                pendingState.update { list ->
                    if (list.any { it.id == event.client.id }) list
                    else list + event.client.toAiClient("Pending Approval")
                }
                logEvent(
                    event.client.id,
                    "AuthPending",
                    "Client requested access without a valid token",
                    "WARNING",
                    "Pending manual approval from ${event.client.remoteAddress}."
                )
            }

            is ServerEvent.Authorized -> {
                pendingState.update { list -> list.filterNot { it.id == event.client.id } }
                connectedState.update { list ->
                    if (list.any { it.id == event.client.id }) list
                    else list + event.client.toAiClient("Authenticated")
                }
                logEvent(
                    event.client.id,
                    "AuthSuccess",
                    "Client authenticated",
                    "SUCCESS",
                    "Connection authorized via ${event.client.transport.name}."
                )
            }

            is ServerEvent.Disconnected -> {
                pendingState.update { list -> list.filterNot { it.id == event.clientId } }
                connectedState.update { list -> list.filterNot { it.id == event.clientId } }
                toolApprovalsState.update { list -> list.filterNot { it.clientId == event.clientId } }
            }

            is ServerEvent.ToolApprovalRequested -> {
                toolApprovalsState.update { list ->
                    if (list.any { it.id == event.request.id }) list else list + event.request
                }
            }

            is ServerEvent.ToolApprovalResolved -> {
                toolApprovalsState.update { list -> list.filterNot { it.id == event.requestId } }
            }

            is ServerEvent.ToolCalled -> {
                val record = event.record
                val status = if (record.outcome == ToolCallOutcome.SUCCESS) "SUCCESS" else "FAILED"
                val id = persistHistory(
                    record.clientId,
                    record.toolName,
                    record.arguments,
                    status,
                    record.message
                )
                appendLog(
                    ToolExecutionLog(
                        id = id,
                        timestamp = record.timestamp,
                        aiClientId = record.clientId,
                        toolName = record.toolName,
                        arguments = record.arguments,
                        status = status,
                        result = record.message
                    )
                )
            }
        }
    }

    private suspend fun logEvent(
        clientId: String,
        toolName: String,
        arguments: String,
        status: String,
        result: String
    ) {
        val id = persistHistory(clientId, toolName, arguments, status, result)
        appendLog(
            ToolExecutionLog(
                id = id,
                timestamp = nowMillis(),
                aiClientId = clientId,
                toolName = toolName,
                arguments = arguments,
                status = status,
                result = result
            )
        )
    }

    private suspend fun persistHistory(
        clientId: String,
        toolName: String,
        arguments: String,
        status: String,
        result: String?
    ): Long {
        return try {
            val storage = database ?: error("Activity storage is unavailable")
            storage.insertCommandHistory(clientId, toolName, arguments, status, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
            withContext(uiDispatcher) {
                storageError = "Unable to save activity '$toolName': " +
                    "${error.message ?: error::class.simpleName}. " +
                    "The entry is only available in memory."
            }
            0L
        }
    }

    private fun appendLog(log: ToolExecutionLog) {
        executionLogsState.update { logs -> (listOf(log) + logs).take(MAX_VISIBLE_LOGS) }
        updateServerActivity(log.toolName, log.status, log.timestamp)
    }

    override fun onCleared() {
        tokenSaveJob?.cancel()
        stopServerForegroundService()
        serverHost.close()
        scope.cancel()
        database?.close()
        super.onCleared()
    }

    private fun McpClient.toAiClient(status: String): AiClient =
        AiClient(
            id = id,
            name = name,
            status = status,
            connectedAt = connectedAt,
            remoteAddress = remoteAddress
        )

    companion object {
        private const val HTTP_PORT = 8080
        private const val HTTPS_PORT = 8443
        private const val MAX_VISIBLE_LOGS = 500
    }
}
