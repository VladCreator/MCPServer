package com.vlad.mcpserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * ViewModel managing settings, server state, connected AI clients, connection approvals, and execution history.
 */
class AppViewModel : ViewModel() {

    var isServerRunning by mutableStateOf(false)
        private set

    var showSettings by mutableStateOf(false)
    var bearerToken by mutableStateOf("mcp_secret_token_2025")
    var sslEnabled by mutableStateOf(false)
    var isDarkMode by mutableStateOf(true)

    val serverName = "MCPServer Host"

    val connectionUrl: String
        get() {
            val scheme = if (sslEnabled) "wss" else "ws"
            val port = if (sslEnabled) 8443 else 8080
            val ip = getLocalIpAddress()
            return "$scheme://$ip:$port/mcp"
        }

    var connectedClients = mutableStateOf(listOf<AiClient>())
        private set

    var pendingClients = mutableStateOf(listOf<AiClient>())
        private set

    var executionLogs = mutableStateOf(listOf<ToolExecutionLog>())
        private set

    private var database: AppDatabase? = null
    private val mcpServerEngine = McpServerEngine(
        getToken = { bearerToken },
        isSslEnabled = { sslEnabled },
        onClientPending = { id, name ->
            val client = AiClient(id = id, name = name, status = "Pending Approval", connectedAt = 1700000000L)
            if (!pendingClients.value.any { it.id == id }) {
                pendingClients.value = pendingClients.value + client
            }
            logCommand(id, "AuthPending", "Client requested access without valid token", "WARNING", "Pending manual approval.")
        },
        onClientAuthorized = { id, name ->
            val client = AiClient(id = id, name = name, status = "Authenticated", connectedAt = 1700000000L)
            if (!connectedClients.value.any { it.id == id }) {
                connectedClients.value = connectedClients.value + client
            }
            logCommand(id, "AuthSuccess", "Client authenticated via Bearer Token", "SUCCESS", "Connection authorized.")
        },
        onClientDisconnected = { id ->
            connectedClients.value = connectedClients.value.filter { it.id != id }
            pendingClients.value = pendingClients.value.filter { it.id != id }
            logCommand(id, "Disconnect", "Client disconnected", "SUCCESS", "Connection closed.")
        },
        onToolExecuted = { clientId, toolName, args ->
            simulateAiToolCall(toolName, args, clientId)
        }
    )

    init {
        try {
            val path = getDatabasePath("mcp_server.db")
            val db = AppDatabase(path)
            db.initDatabase()
            database = db
            
            // Load persisted settings
            db.getSetting("bearer_token")?.let { bearerToken = it }
            db.getSetting("ssl_enabled")?.let { sslEnabled = it.toBoolean() }
            db.getSetting("dark_mode")?.let { isDarkMode = it.toBoolean() }

            loadHistory()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleSettings() {
        showSettings = !showSettings
    }

    fun updateBearerToken(newToken: String) {
        bearerToken = newToken
        database?.saveSetting("bearer_token", newToken)
    }

    /**
     * Generates a secure 14-character random Bearer token and saves it.
     */
    fun generateSecureToken(): String {
        val charPool = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        val newToken = (1..14)
            .map { kotlin.random.Random.nextInt(0, charPool.length) }
            .map(charPool::get)
            .joinToString("")
        updateBearerToken(newToken)
        return newToken
    }

    fun updateSslEnabled(enabled: Boolean) {
        sslEnabled = enabled
        database?.saveSetting("ssl_enabled", enabled.toString())
    }

    fun updateDarkMode(enabled: Boolean) {
        isDarkMode = enabled
        database?.saveSetting("dark_mode", enabled.toString())
    }

    /**
     * Toggles the MCP server active state, enabling/disabling port 8080/8443.
     */
    fun toggleServer() {
        isServerRunning = !isServerRunning
        if (isServerRunning) {
            mcpServerEngine.start()
            val port = if (sslEnabled) "8443 (HTTPS/WSS)" else "8080 (HTTP/WS)"
            logCommand("System", "StartServer", "Port: $port", "SUCCESS", "MCP Server started successfully.")
        } else {
            mcpServerEngine.stop()
            connectedClients.value = emptyList()
            pendingClients.value = emptyList()
            logCommand("System", "StopServer", "", "SUCCESS", "MCP Server stopped.")
        }
    }

    /**
     * Manually approves a pending AI client connection request.
     */
    fun approveClient(clientId: String) {
        val client = pendingClients.value.find { it.id == clientId } ?: return
        pendingClients.value = pendingClients.value.filter { it.id != clientId }
        val approvedClient = client.copy(status = "Manually Approved")
        connectedClients.value = connectedClients.value + approvedClient
        logCommand(clientId, "ManualApproval", "User approved client connection", "SUCCESS", "Client moved to active.")
    }

    /**
     * Manually rejects a pending AI client connection request.
     */
    fun rejectClient(clientId: String) {
        pendingClients.value = pendingClients.value.filter { it.id != clientId }
        logCommand(clientId, "ManualReject", "User rejected client connection", "FAILED", "Connection denied.")
    }

    /**
     * Simulates an AI client invoking a device tool (e.g. touch test, take photo, read file, notification).
     */
    fun simulateAiToolCall(toolName: String, arguments: String, clientId: String = connectedClients.value.firstOrNull()?.id ?: "unknown-ai") {
        val result = when (toolName) {
            "touch_test" -> "Touch event simulated at coordinates (500, 800) successfully."
            "take_photo" -> "Photo captured successfully: /storage/emulated/0/DCIM/mcp_snapshot_01.jpg"
            "read_file" -> "File read successfully: /storage/emulated/0/Documents/config.json"
            "create_notification" -> "System notification posted successfully ID: 101"
            else -> "Tool executed successfully ($arguments)."
        }
        logCommand(clientId, toolName, arguments, "SUCCESS", result)
    }

    private fun logCommand(aiClientId: String, toolName: String, arguments: String, status: String, result: String) {
        database?.insertCommandHistory(aiClientId, toolName, arguments, status, result)
        loadHistory()
    }

    private fun loadHistory() {
        database?.let { db ->
            executionLogs.value = db.getCommandHistories()
        }
    }
}
