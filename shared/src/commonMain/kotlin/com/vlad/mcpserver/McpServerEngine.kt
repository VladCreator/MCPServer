package com.vlad.mcpserver

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*

/**
 * Embedded Ktor HTTP & WebSocket server acting as the Model Context Protocol (MCP) host on port 8080.
 */
class McpServerEngine(
    private val onClientConnected: (String, String) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onToolExecuted: (String, String, String) -> Unit
) {
    private var embeddedServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Starts the embedded Ktor server on port 8080.
     */
    fun start() {
        if (embeddedServer != null) return
        try {
            embeddedServer = embeddedServer(CIO, port = 8080) {
                install(WebSockets)
                routing {
                    get("/") {
                        call.respondText("MCPServer Host v0.0.1 running and ready for AI connections.")
                    }
                    get("/status") {
                        call.respondText("{\"status\":\"active\",\"version\":\"0.0.1\"}")
                    }
                    webSocket("/mcp") {
                        val clientId = "ai-client-ext"
                        onClientConnected(clientId, "Connected AI Agent (WebSocket)")
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    onToolExecuted(clientId, "mcp_command", text)
                                    outgoing.send(Frame.Text("{\"status\":\"success\",\"message\":\"Command processed\"}"))
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            onClientDisconnected(clientId)
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Stops the embedded Ktor server.
     */
    fun stop() {
        try {
            embeddedServer?.stop(1000, 2000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        embeddedServer = null
    }
}
