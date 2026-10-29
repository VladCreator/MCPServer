package com.vlad.mcpserver.mcp

import android.content.Context
import com.vlad.mcpserver.appContext
import com.vlad.mcpserver.getAllLocalIpv4Addresses
import com.vlad.mcpserver.isAppContextInitialized
import com.vlad.mcpserver.nowMillis
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Writer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val SESSION_HEADER = "Mcp-Session-Id"
private const val DEFAULT_HTTP_PORT = 8080
private const val PORT_CHECK_ATTEMPTS = 6
private const val PORT_CHECK_DELAY_MS = 500L
private const val CLOSE_TIMEOUT_MS = 1_000L
private const val DEFAULT_HTTPS_PORT = 8443
private const val APPROVAL_TIMEOUT_MS = 60_000L
private const val SESSION_IDLE_TIMEOUT_MS = 10 * 60_000L
private const val MAX_BODY_BYTES = 256 * 1024
private const val TOOL_APPROVAL_TIMEOUT_MS = 60_000L
private const val KEEP_ALIVE_INTERVAL_MS = 15_000L

actual fun createMcpServerHost(
    getToken: () -> String,
    isSslEnabled: () -> Boolean,
    shouldConfirmTools: () -> Boolean
): McpServerHost {
    if (!isAppContextInitialized()) {
        return UnsupportedMcpServerHost()
    }
    return AndroidMcpServerHost(appContext, getToken, isSslEnabled, shouldConfirmTools)
}

private val secureRandom = SecureRandom()

actual fun newSecureToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Embedded Ktor Tester for Android.
 *
 * Transports:
 *  - Streamable HTTP: POST/GET/DELETE /mcp
 *  - HTTP+SSE (legacy): GET /sse, POST /message?sessionId=...
 *  - WebSocket (legacy/custom): /ws
 *
 * All transports share the same session registry, approval gate and dispatcher.
 */
private class AndroidMcpServerHost(
    private val context: Context,
    private val getToken: () -> String,
    private val isSslEnabled: () -> Boolean,
    private val shouldConfirmTools: () -> Boolean
) : McpServerHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private val statusState = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    private val eventFlow = MutableSharedFlow<ServerEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val pending = ConcurrentHashMap<String, McpSession>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val toolApprovals = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    private var engine: EmbeddedServer<*, *>? = null
    private var reaper: Job? = null

    private val dispatcher = McpDispatcher(
        tools = McpTools.available(),
        executor = AndroidToolExecutor(context) { clientId, toolName, arguments ->
            requestToolApproval(clientId, toolName, arguments)
        },
        onToolCall = { record -> eventFlow.tryEmit(ServerEvent.ToolCalled(record)) }
    )

    override val status: StateFlow<ServerStatus> = statusState.asStateFlow()
    override val events: SharedFlow<ServerEvent> = eventFlow.asSharedFlow()

    override fun start() {
        if (statusState.value is ServerStatus.Running || statusState.value is ServerStatus.Starting) return
        statusState.value = ServerStatus.Starting
        scope.launch {
            lifecycleMutex.withLock {
                try {
                    startInternal()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    synchronized(this@AndroidMcpServerHost) { engine = null }
                    statusState.value = ServerStatus.Failed(
                        error.message ?: error::class.simpleName ?: "Unable to start the server"
                    )
                }
            }
        }
    }

    override fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                stopInternal()
            }
        }
    }

    override fun approve(clientId: String) {
        val session = pending[clientId] ?: sessions[clientId] ?: return
        session.approve()
    }

    override fun reject(clientId: String) {
        val session = pending[clientId] ?: sessions[clientId] ?: return
        session.reject()
    }

    override fun resolveToolApproval(requestId: String, approved: Boolean) {
        toolApprovals.remove(requestId)?.complete(approved)
    }

    override fun close() {
        reaper?.cancel()
        reaper = null
        val current = engine
        engine = null
        runCatching { current?.stop(0, CLOSE_TIMEOUT_MS) }
        cancelToolApprovals()
        closeAllSessions()
        scope.cancel()
    }

    private suspend fun startInternal() {
        reaper?.cancel()
        reaper = null

        val secure = isSslEnabled()
        val serverPort = if (secure) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
        val tls = if (secure) {
            AndroidTls.loadOrCreate(context, getAllLocalIpv4Addresses())
        } else {
            null
        }

        ensurePortFree(serverPort)

        val server = if (secure && tls != null) {
            embeddedServer(Netty, configure = {
                sslConnector(
                    keyStore = tls.keyStore,
                    keyAlias = AndroidTls.ALIAS,
                    keyStorePassword = { AndroidTls.password() },
                    privateKeyPassword = { AndroidTls.password() }
                ) {
                    host = "0.0.0.0"
                    this.port = serverPort
                }
            }) {
                configureModule()
            }
        } else {
            embeddedServer(CIO, configure = {
                connector {
                    host = "0.0.0.0"
                    this.port = serverPort
                }
            }) {
                configureModule()
            }
        }

        server.start(wait = false)
        synchronized(this) { engine = server }
        statusState.value = ServerStatus.Running(
            port = serverPort,
            secure = secure,
            fingerprint = tls?.fingerprint
        )
        startReaper()
    }

    /**
     * Binding is asynchronous in the engines, so a busy port would crash the process from a
     * background coroutine. Check first, giving a previous instance a moment to release it.
     */
    private suspend fun ensurePortFree(port: Int) {
        repeat(PORT_CHECK_ATTEMPTS) { attempt ->
            val free = runCatching {
                java.net.ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(port))
                }
            }.isSuccess
            if (free) return
            if (attempt < PORT_CHECK_ATTEMPTS - 1) delay(PORT_CHECK_DELAY_MS)
        }
        throw IllegalStateException(
            "Port $port is already in use (a previous server may still be closing). " +
                "Wait a few seconds and start again, or force-stop Tester."
        )
    }

    private suspend fun stopInternal() {
        reaper?.cancel()
        reaper = null
        val current = synchronized(this) { engine.also { engine = null } }
        if (current != null) {
            runCatching { current.stop(500, 1_500) }
        }
        cancelToolApprovals()
        closeAllSessions()
        statusState.value = ServerStatus.Stopped
    }

    private fun startReaper() {
        reaper?.cancel()
        reaper = scope.launch {
            while (isActive) {
                delay(60_000L)
                val now = nowMillis()
                val expired = lastSeen.entries
                    .filter { now - it.value > SESSION_IDLE_TIMEOUT_MS }
                    .map { it.key }
                for (id in expired) {
                    sessions[id]?.let { closeSession(it) }
                }
            }
        }
    }

    private fun Application.configureModule() {
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 30.seconds
        }

        routing {
            get("/") {
                call.respondText(
                    "Tester v${McpDispatcher.SERVER_VERSION}\n" +
                        "Streamable HTTP: POST/GET/DELETE /mcp\n" +
                        "SSE: GET /sse, POST /message\n" +
                        "WebSocket (legacy): /ws\n"
                )
            }
            get("/health") {
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            }
            get("/status") {
                handleStatus(call)
            }
            post("/mcp") {
                handleStreamablePost(call)
            }
            get("/mcp") {
                handleStreamableGet(call)
            }
            delete("/mcp") {
                handleStreamableDelete(call)
            }
            get("/sse") {
                handleSse(call)
            }
            get("/mcp/sse") {
                handleSse(call)
            }
            post("/message") {
                handleSseMessage(call)
            }
            webSocket("/mcp") {
                handleWebSocket()
            }
            webSocket("/ws") {
                handleWebSocket()
            }
        }
    }

    private suspend fun handleStatus(call: ApplicationCall) {
        val current = statusState.value
        val statusName = when (current) {
            is ServerStatus.Stopped -> "stopped"
            is ServerStatus.Starting -> "starting"
            is ServerStatus.Running -> "running"
            is ServerStatus.Failed -> "failed"
            ServerStatus.Unsupported -> "unsupported"
        }
        val payload = buildJsonObject {
            put("status", statusName)
            put("version", McpDispatcher.SERVER_VERSION)
            put("ssl", isSslEnabled())
            put("sessions", sessions.size)
            put("pending", pending.size)
            put("tools", McpTools.available().size)
            if (current is ServerStatus.Running) {
                put("port", current.port)
                current.fingerprint?.let { put("fingerprint", it) }
            }
            if (current is ServerStatus.Failed) {
                put("error", current.message)
            }
        }
        call.respondText(payload.toString(), ContentType.Application.Json)
    }

    private suspend fun handleStreamablePost(call: ApplicationCall) {
        if (!call.isOriginAllowed()) {
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }

        val sessionHeader = call.request.headers[SESSION_HEADER]
        val existing = sessionHeader?.let { sessions[it] }
        if (sessionHeader != null && existing == null) {
            call.respondText("Session not found", status = HttpStatusCode.NotFound)
            return
        }
        val session = existing ?: newSession(call, Transport.STREAMABLE_HTTP)
        touch(session)

        if (!ensureAuthorized(session, call)) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }

        val body = readBody(call) ?: return
        val response = dispatcher.handle(session, body)

        call.response.headers.append(SESSION_HEADER, session.id)
        if (response == null) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        val accept = call.request.headers[HttpHeaders.Accept].orEmpty()
        val wantsSseOnly = !accept.contains("application/json", ignoreCase = true) &&
            accept.contains("text/event-stream", ignoreCase = true)
        if (wantsSseOnly) {
            call.respondTextWriter(ContentType.Text.EventStream) {
                write("event: message\ndata: $response\n\n")
                flush()
            }
        } else {
            call.respondText(response.toString(), ContentType.Application.Json)
        }
    }

    private suspend fun handleStreamableGet(call: ApplicationCall) {
        if (!call.isOriginAllowed()) {
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        val sessionId = call.request.headers[SESSION_HEADER]
        val session = sessionId?.let { sessions[it] }
        if (session == null) {
            call.respondText("Session not found", status = HttpStatusCode.NotFound)
            return
        }
        touch(session)
        if (!ensureAuthorized(session, call)) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }

        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.respondTextWriter(ContentType.Text.EventStream) {
            write(": connected\n\n")
            flush()
            streamOutbox(session, this)
        }
    }

    private suspend fun handleStreamableDelete(call: ApplicationCall) {
        val sessionId = call.request.headers[SESSION_HEADER]
        val session = sessionId?.let { sessions[it] }
        if (session == null) {
            call.respondText("Session not found", status = HttpStatusCode.NotFound)
            return
        }
        closeSession(session)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun handleSse(call: ApplicationCall) {
        if (!call.isOriginAllowed()) {
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        val session = newSession(call, Transport.SSE)
        touch(session)
        try {
            if (!ensureAuthorized(session, call)) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return
            }

            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                write("event: endpoint\ndata: /message?sessionId=${session.id}\n\n")
                flush()
                streamOutbox(session, this)
            }
        } finally {
            closeSession(session)
        }
    }

    private suspend fun handleSseMessage(call: ApplicationCall) {
        if (!call.isOriginAllowed()) {
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        val sessionId = call.request.queryParameters["sessionId"]
        val session = sessionId?.let { sessions[it] }
        if (session == null) {
            call.respondText("Session not found", status = HttpStatusCode.NotFound)
            return
        }
        touch(session)

        if (!session.isAuthorized && !ensureAuthorized(session, call)) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }

        val body = readBody(call) ?: return
        val response = dispatcher.handle(session, body)
        if (response != null) {
            val queued = session.outbox.trySend(response.toString())
            if (queued.isFailure) {
                call.respondText("Session outbox is unavailable", status = HttpStatusCode.ServiceUnavailable)
                return
            }
        }
        call.respond(HttpStatusCode.Accepted)
    }

    private suspend fun DefaultWebSocketServerSession.handleWebSocket() {
        val session = newSession(call, Transport.WEBSOCKET)
        touch(session)
        try {
            if (!ensureAuthorized(session, call)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return
            }
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (text.length > MAX_BODY_BYTES) {
                    close(CloseReason(CloseReason.Codes.TOO_BIG, "Message too large"))
                    break
                }
                touch(session)
                val response = dispatcher.handle(session, text)
                if (response != null) {
                    outgoing.send(Frame.Text(response.toString()))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ClosedReceiveChannelException) {
            // Normal disconnect.
        } catch (_: Throwable) {
            runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Server error")) }
        } finally {
            closeSession(session)
        }
    }

    private suspend fun streamOutbox(session: McpSession, writer: Writer) {
        while (true) {
            val result = withTimeoutOrNull(KEEP_ALIVE_INTERVAL_MS) {
                session.outbox.receiveCatching()
            }
            if (result == null) {
                lastSeen[session.id] = nowMillis()
                writer.write(": keep-alive\n\n")
                writer.flush()
                continue
            }
            val message = result.getOrNull() ?: break
            lastSeen[session.id] = nowMillis()
            writer.write("event: message\ndata: $message\n\n")
            writer.flush()
        }
    }

    private suspend fun ensureAuthorized(session: McpSession, call: ApplicationCall): Boolean {
        if (session.isAuthorized) return true

        if (BearerAuth.isValid(call.request.headers[HttpHeaders.Authorization], getToken())) {
            if (session.markAuthorized()) {
                eventFlow.tryEmit(ServerEvent.Authorized(session.client))
            }
            return true
        }

        if (session.markPendingOnce()) {
            pending[session.id] = session
            eventFlow.tryEmit(ServerEvent.Pending(session.client))
        }

        val approved = try {
            session.awaitDecision(APPROVAL_TIMEOUT_MS)
        } catch (error: CancellationException) {
            closeSession(session)
            throw error
        }

        pending.remove(session.id)
        if (approved) {
            if (session.markAuthorized()) {
                eventFlow.tryEmit(ServerEvent.Authorized(session.client))
            }
            return true
        }

        closeSession(session)
        return false
    }

    private suspend fun requestToolApproval(
        clientId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject
    ): Boolean {
        if (!shouldConfirmTools() || !McpTools.isSensitive(toolName)) return true

        val client = sessions[clientId]?.client
        val request = ToolApprovalRequest(
            id = UUID.randomUUID().toString(),
            clientId = clientId,
            clientName = client?.name ?: "Unknown client",
            toolName = toolName,
            arguments = if (toolName == "run_route") arguments.toString() else arguments.toString().take(2_000),
            requestedAt = nowMillis()
        )
        val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
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

    private fun cancelToolApprovals() {
        val snapshot = toolApprovals.values.toList()
        toolApprovals.clear()
        snapshot.forEach { it.complete(false) }
    }

    private fun newSession(call: ApplicationCall, transport: Transport): McpSession {
        val id = UUID.randomUUID().toString()
        val name = call.request.headers["X-Client-Name"]
            ?.take(64)
            ?.filter { it.code in 32..126 }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "External AI Agent"
        val remote = runCatching { call.request.origin.remoteHost }.getOrDefault("unknown")
        val client = McpClient(
            id = id,
            name = name,
            remoteAddress = remote,
            transport = transport,
            connectedAt = nowMillis()
        )
        val session = McpSession(client)
        sessions[id] = session
        lastSeen[id] = nowMillis()
        return session
    }

    private fun touch(session: McpSession) {
        lastSeen[session.id] = nowMillis()
    }

    private fun closeSession(session: McpSession) {
        val removed = sessions.remove(session.id)
        pending.remove(session.id)
        lastSeen.remove(session.id)
        session.close()
        if (removed != null) {
            eventFlow.tryEmit(ServerEvent.Disconnected(session.id))
        }
    }

    private fun closeAllSessions() {
        val snapshot = sessions.keys.toList()
        for (id in snapshot) {
            sessions[id]?.let { closeSession(it) }
        }
        pending.clear()
        lastSeen.clear()
    }

    private suspend fun readBody(call: ApplicationCall): String? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MAX_BODY_BYTES) {
            call.respondText("Request body too large", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        val body = call.receiveText()
        if (body.length > MAX_BODY_BYTES) {
            call.respondText("Request body too large", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        return body
    }

    /**
     * Blocks cross-site browser requests (DNS rebinding / CSRF). Native MCP
     * clients do not send an Origin header.
     */
    private fun ApplicationCall.isOriginAllowed(): Boolean {
        val origin = request.headers[HttpHeaders.Origin] ?: return true
        return origin == "http://localhost" ||
            origin.startsWith("http://localhost:") ||
            origin.startsWith("http://127.0.0.1") ||
            origin == "https://localhost" ||
            origin.startsWith("https://localhost:") ||
            origin.startsWith("https://127.0.0.1")
    }
}
