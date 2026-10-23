package com.vlad.mcpserver

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.security.KeyStore

/**
 * Android actual implementation of McpServerEngine using Ktor Netty (for HTTPS) and CIO (for HTTP) engines.
 * Supports both WebSocket (/mcp) and SSE (/sse, /mcp/sse, /message) transports.
 */
actual class McpServerEngine actual constructor(
    private val getToken: () -> String,
    private val isSslEnabled: () -> Boolean,
    private val onClientPending: (String, String) -> Unit,
    private val onClientAuthorized: (String, String) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onToolExecuted: (String, String, String) -> Unit
) {
    private var embeddedServer: EmbeddedServer<*, *>? = null

    actual fun start() {
        if (embeddedServer != null) return
        try {
            val ssl = isSslEnabled()
            val targetPort = if (ssl) 8443 else 8080
            val protocol = if (ssl) "HTTPS/WSS" else "HTTP/WS"

            if (ssl) {
                // Use Netty engine for HTTPS/WSS support on Android
                embeddedServer = embeddedServer(Netty, configure = {
                    sslConnector(
                        keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) },
                        keyAlias = "mcpkey",
                        keyStorePassword = { "mcp_password".toCharArray() },
                        privateKeyPassword = { "mcp_password".toCharArray() }
                    ) {
                        port = targetPort
                        host = "0.0.0.0"
                    }
                }) {
                    configureServerModule(protocol, targetPort)
                }.start(wait = false)
            } else {
                // Use CIO engine for lightweight HTTP/WS on port 8080
                embeddedServer = embeddedServer(CIO, configure = {
                    connector {
                        port = targetPort
                        host = "0.0.0.0"
                    }
                }) {
                    configureServerModule(protocol, targetPort)
                }.start(wait = false)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            // Gracefully handle any SSL/Engine initialization errors
        }
    }

    private fun Application.configureServerModule(protocol: String, targetPort: Int) {
        install(WebSockets)
        routing {
            get("/") {
                call.respondText("MCPServer Host v0.0.1 running ($protocol) on port $targetPort and ready for AI connections.")
            }
            get("/status") {
                call.respondText("{\"status\":\"active\",\"version\":\"0.0.1\",\"ssl\":${isSslEnabled()},\"port\":$targetPort}")
            }

            // SSE Transport Endpoint (/sse and /mcp/sse)
            get("/sse") {
                handleSseConnection()
            }
            get("/mcp/sse") {
                handleSseConnection()
            }

            // SSE Message Post Endpoint (/message)
            post("/message") {
                val sessionId = call.request.queryParameters["sessionId"] ?: "sse-agent-ext"
                val requestBody = call.receiveText()
                onToolExecuted(sessionId, "mcp_rpc", requestBody)
                call.respondText(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"processed\"}}",
                    ContentType.Application.Json
                )
            }

            // WebSocket Transport Endpoint (/mcp)
            webSocket("/mcp") {
                val authHeader = call.request.headers["Authorization"]
                val clientId = "ws-agent-ext"
                val clientName = call.request.headers["X-Client-Name"] ?: "External AI Agent (WS)"

                val configuredToken = getToken()
                val isAuthenticated = authHeader != null && authHeader == "Bearer $configuredToken"

                if (isAuthenticated) {
                    onClientAuthorized(clientId, clientName)
                } else {
                    onClientPending(clientId, clientName)
                }

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
    }

    private suspend fun RoutingContext.handleSseConnection() {
        val authHeader = call.request.headers["Authorization"]
        val clientId = "sse-agent-ext"
        val clientName = call.request.headers["X-Client-Name"] ?: "External AI Agent (SSE)"

        val configuredToken = getToken()
        val isAuthenticated = authHeader != null && authHeader == "Bearer $configuredToken"

        if (isAuthenticated) {
            onClientAuthorized(clientId, clientName)
        } else {
            onClientPending(clientId, clientName)
        }

        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.respondTextWriter(ContentType.Text.EventStream) {
            write("event: endpoint\ndata: /message?sessionId=$clientId\n\n")
            flush()
        }
    }

    actual fun stop() {
        try {
            embeddedServer?.stop(1000, 2000)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        embeddedServer = null
    }
}
