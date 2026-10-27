package com.vlad.mcpserver.mcp

import com.vlad.mcpserver.nowMillis
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUUID

/**
 * Embedded iOS QA MCP service. It listens on localhost only and is intended to
 * run while the host app is in the foreground. Real UI interaction stays in
 * XCUITest; this host provides session, state, instructions and evidence.
 */
class IosMcpServerHost(
    private val getToken: () -> String,
    private val shouldConfirmTools: () -> Boolean
) : McpServerHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private val statusState = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    private val eventFlow = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 16)
    private val sessions = mutableMapOf<String, McpSession>()
    private val pending = mutableMapOf<String, McpSession>()
    private val toolApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private var engine: EmbeddedServer<*, *>? = null
    private var dispatcher: McpDispatcher? = null

    override val status: StateFlow<ServerStatus> = statusState.asStateFlow()
    override val events: SharedFlow<ServerEvent> = eventFlow.asSharedFlow()

    override fun start() {
        if (statusState.value is ServerStatus.Running || statusState.value is ServerStatus.Starting) return
        statusState.value = ServerStatus.Starting
        scope.launch {
            mutex.withLock {
                try {
                    startInternal()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    statusState.value = ServerStatus.Failed(
                        error.message ?: error::class.simpleName ?: "Unable to start the iOS QA service"
                    )
                }
            }
        }
    }

    override fun stop() {
        scope.launch {
            mutex.withLock {
                val current = engine
                engine = null
                runCatching { current?.stop(0, 1_000) }
                closeAllSessions()
                statusState.value = ServerStatus.Stopped
            }
        }
    }

    override fun approve(clientId: String) {
        pending[clientId]?.approve() ?: sessions[clientId]?.approve()
    }

    override fun reject(clientId: String) {
        pending[clientId]?.reject() ?: sessions[clientId]?.reject()
    }

    override fun resolveToolApproval(requestId: String, approved: Boolean) {
        toolApprovals.remove(requestId)?.complete(approved)
    }

    override fun close() {
        runCatching { engine?.stop(0, 500) }
        engine = null
        closeAllSessions()
        scope.cancel()
    }

    private suspend fun startInternal() {
        if (!isPortAvailable()) {
            statusState.value = ServerStatus.Failed(
                "Port $HTTP_PORT is already in use. Stop the other server and try again."
            )
            return
        }
        val qaExecutor = IosQaToolExecutor { clientId, toolName, arguments ->
            requestToolApproval(clientId, toolName, arguments)
        }
        dispatcher = McpDispatcher(
            tools = McpTools.iosAvailable(),
            executor = qaExecutor,
            onToolCall = { record -> eventFlow.tryEmit(ServerEvent.ToolCalled(record)) },
            platformLabel = "iOS QA"
        )
        val server = embeddedServer(CIO, port = HTTP_PORT, host = "127.0.0.1") {
            configureModule()
        }
        server.start(wait = false)
        engine = server
        statusState.value = ServerStatus.Running(port = HTTP_PORT, secure = false, fingerprint = null)
    }

    private suspend fun isPortAvailable(): Boolean {
        val selector = SelectorManager(Dispatchers.Default)
        return try {
            val socket = aSocket(selector).tcp().bind("127.0.0.1", HTTP_PORT)
            socket.close()
            true
        } catch (_: Throwable) {
            false
        } finally {
            selector.close()
        }
    }

    private fun Application.configureModule() {
        routing {
            get("/") {
                call.respondText(
                    "Tester iOS QA service v${McpDispatcher.SERVER_VERSION}\n" +
                        "Streamable HTTP: POST/DELETE /mcp\n" +
                        "Status: GET /status\n"
                )
            }
            get("/health") {
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            }
            get("/status") {
                val running = statusState.value as? ServerStatus.Running
                val payload = buildJsonObject {
                    put("status", if (running != null) "running" else "stopped")
                    put("version", McpDispatcher.SERVER_VERSION)
                    put("platform", "ios")
                    put("ssl", false)
                    put("sessions", sessions.size)
                    put("pending", pending.size)
                    put("tools", McpTools.iosAvailable().size)
                    running?.let { put("port", it.port) }
                }
                call.respondText(payload.toString(), ContentType.Application.Json)
            }
            post("/mcp") {
                handleMcp(call)
            }
            delete("/mcp") {
                val id = call.request.headers["Mcp-Session-Id"]
                if (id != null) {
                    mutex.withLock {
                        sessions.remove(id)?.let { it.close() }
                        pending.remove(id)
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/mcp") {
                call.response.header(HttpHeaders.Allow, "POST, DELETE")
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
        }
    }

    private suspend fun handleMcp(call: ApplicationCall) {
        val body = call.receiveText()
        if (body.encodeToByteArray().size > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        val requestedId = call.request.headers["Mcp-Session-Id"]
        val session = mutex.withLock {
            requestedId?.let { sessions[it] } ?: run {
                val id = NSUUID().UUIDString
                val client = McpClient(
                    id = id,
                    name = call.request.headers["X-Client-Name"]?.take(64) ?: "MCP Client",
                    remoteAddress = "localhost",
                    transport = Transport.STREAMABLE_HTTP,
                    connectedAt = nowMillis()
                )
                McpSession(client).also { sessions[id] = it }
            }
        }

        if (!session.isAuthorized && !authorize(call, session)) {
            mutex.withLock { sessions.remove(session.id) }
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val currentDispatcher = dispatcher
        if (currentDispatcher == null) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return
        }
        val response = currentDispatcher.handle(session, body)
        call.response.header("Mcp-Session-Id", session.id)
        if (response == null) {
            call.respond(HttpStatusCode.Accepted)
        } else {
            call.respondText(response.toString(), ContentType.Application.Json)
        }
    }

    private suspend fun authorize(call: ApplicationCall, session: McpSession): Boolean {
        if (isTokenValid(call)) {
            if (session.markAuthorized()) {
                eventFlow.emit(ServerEvent.Authorized(session.client))
            }
            return true
        }

        val first = session.markPendingOnce()
        mutex.withLock { pending[session.id] = session }
        if (first) eventFlow.emit(ServerEvent.Pending(session.client))
        val approved = try {
            session.awaitDecision(APPROVAL_TIMEOUT_MS)
        } catch (error: CancellationException) {
            mutex.withLock { pending.remove(session.id) }
            throw error
        }
        mutex.withLock { pending.remove(session.id) }
        if (!approved) {
            session.close()
            return false
        }
        if (session.markAuthorized()) {
            eventFlow.emit(ServerEvent.Authorized(session.client))
        }
        return true
    }

    private fun isTokenValid(call: ApplicationCall): Boolean {
        val token = getToken()
        if (token.isBlank()) return false
        val expected = "Bearer $token"
        val actual = call.request.headers[HttpHeaders.Authorization] ?: return false
        if (actual.length != expected.length) return false
        var difference = 0
        for (index in expected.indices) {
            difference = difference or (expected[index].code xor actual[index].code)
        }
        return difference == 0
    }

    private suspend fun requestToolApproval(
        clientId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject
    ): Boolean {
        if (!shouldConfirmTools() || !McpTools.isSensitive(toolName)) return true
        val client = mutex.withLock { sessions[clientId]?.client }
        val request = ToolApprovalRequest(
            id = NSUUID().UUIDString,
            clientId = clientId,
            clientName = client?.name ?: "Unknown client",
            toolName = toolName,
            arguments = arguments.toString().take(2_000),
            requestedAt = nowMillis()
        )
        val decision = CompletableDeferred<Boolean>()
        toolApprovals[request.id] = decision
        eventFlow.emit(ServerEvent.ToolApprovalRequested(request))
        val approved = try {
            withTimeoutOrNull(TOOL_APPROVAL_TIMEOUT_MS) { decision.await() } ?: false
        } catch (error: CancellationException) {
            toolApprovals.remove(request.id)
            eventFlow.tryEmit(ServerEvent.ToolApprovalResolved(request.id, false))
            throw error
        }
        toolApprovals.remove(request.id)
        eventFlow.tryEmit(ServerEvent.ToolApprovalResolved(request.id, approved))
        return approved
    }

    private fun closeAllSessions() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        pending.clear()
        toolApprovals.values.forEach { it.complete(false) }
        toolApprovals.clear()
    }

    private companion object {
        const val HTTP_PORT = 8080
        const val MAX_BODY_BYTES = 256 * 1024
        const val APPROVAL_TIMEOUT_MS = 60_000L
        const val TOOL_APPROVAL_TIMEOUT_MS = 60_000L
    }
}
