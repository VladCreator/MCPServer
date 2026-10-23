package com.vlad.mcpserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * ViewModel managing the state of the MCP server, connected AI clients, and execution history.
 */
class AppViewModel : ViewModel() {

    var isServerRunning by mutableStateOf(false)
        private set

    var connectedClients = mutableStateOf(listOf<AiClient>())
        private set

    var executionLogs = mutableStateOf(listOf<ToolExecutionLog>())
        private set

    private var database: AppDatabase? = null
    private val mcpServerEngine = McpServerEngine(
        onClientConnected = { id, name ->
            connectedClients.value = listOf(
                AiClient(id = id, name = name, status = "Connected", connectedAt = 1700000000L)
            )
            logCommand(id, "Connect", "Client connected to MCP port 8080", "SUCCESS", "Connection established.")
        },
        onClientDisconnected = { id ->
            connectedClients.value = emptyList()
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
            loadHistory()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Toggles the MCP server active state, enabling/disabling port 8080.
     */
    fun toggleServer() {
        isServerRunning = !isServerRunning
        if (isServerRunning) {
            mcpServerEngine.start()
            connectedClients.value = listOf(
                AiClient(
                    id = "ai-agent-01",
                    name = "Claude Agent (Vision & UI Test)",
                    status = "Ready on Port 8080",
                    connectedAt = 1700000000L
                )
            )
            logCommand("System", "StartServer", "Port: 8080", "SUCCESS", "MCP Server started successfully on port 8080.")
        } else {
            mcpServerEngine.stop()
            connectedClients.value = emptyList()
            logCommand("System", "StopServer", "", "SUCCESS", "MCP Server stopped.")
        }
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
