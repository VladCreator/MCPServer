package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.JsonRpc
import com.vlad.mcpserver.mcp.JsonRpcErrorCode
import com.vlad.mcpserver.mcp.McpClient
import com.vlad.mcpserver.mcp.McpDispatcher
import com.vlad.mcpserver.mcp.McpSession
import com.vlad.mcpserver.mcp.McpTools
import com.vlad.mcpserver.mcp.ToolExecutor
import com.vlad.mcpserver.mcp.ToolResult
import com.vlad.mcpserver.mcp.Transport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpDispatcherTest {

    @Test
    fun initializeNegotiatesProtocolVersion() = runTest {
        val dispatcher = dispatcher()
        val session = session()

        val response = dispatcher.handle(session, initializeJson("2025-06-18"))!!.jsonObject
        val result = response["result"]!!.jsonObject

        assertEquals("2.0", response["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(1, response["id"]!!.jsonPrimitive.int)
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("MCPServer Host", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(session.initialized)
    }

    @Test
    fun initializeEchoesOlderSupportedVersion() = runTest {
        val dispatcher = dispatcher()
        val response = dispatcher.handle(session(), initializeJson("2024-11-05"))!!.jsonObject
        val result = response["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsListRequiresInitialize() = runTest {
        val dispatcher = dispatcher()
        val response = dispatcher.handle(session(), requestJson(2, "tools/list"))!!.jsonObject
        assertEquals(JsonRpcErrorCode.SERVER_NOT_INITIALIZED, errorCode(response))
    }

    @Test
    fun toolsListOnlyExposesAvailableTools() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(session, requestJson(2, "tools/list"))!!.jsonObject
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

        assertTrue(tools.contains("read_file"))
        assertTrue(tools.contains("create_notification"))
        assertTrue(tools.contains("get_device_info"))
        assertTrue(tools.contains("touch_test"))
        assertTrue(tools.contains("take_photo"))
        assertTrue(tools.contains("launch_app"))
        listOf("get_screen_tree", "take_screenshot", "click_element", "input_text", "scroll", "global_action")
            .forEach { assertTrue(tools.contains(it), "missing $it") }
    }

    @Test
    fun toolsCallInvokesExecutorAndReturnsContent() = runTest {
        val executor = FakeExecutor(ToolResult("file contents"))
        val dispatcher = dispatcher(executor)
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(
            session,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/tmp/a.txt"}}}"""
        )!!.jsonObject

        val result = response["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("file contents", text)
        assertEquals("read_file", executor.lastTool)
        assertEquals("test-client", executor.lastClientId)
        assertEquals("/tmp/a.txt", executor.lastArguments!!["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownToolIsInvalidParams() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(
            session,
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}"""
        )!!.jsonObject

        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(response))
    }

    @Test
    fun missingRequiredArgumentIsInvalidParams() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(
            session,
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"read_file","arguments":{}}}"""
        )!!.jsonObject

        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(response))
    }

    @Test
    fun touchTestRequiresCoordinates() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(
            session,
            """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"touch_test","arguments":{}}}"""
        )!!.jsonObject

        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(response))
    }

    @Test
    fun toolExecutionFailureIsReportedAsToolError() = runTest {
        val executor = FakeExecutor(ToolResult("boom", isError = true))
        val dispatcher = dispatcher(executor)
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(
            session,
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"create_notification","arguments":{"title":"hi"}}}"""
        )!!.jsonObject

        val result = response["result"]!!.jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(session, requestJson(7, "resources/list"))!!.jsonObject
        assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, errorCode(response))
    }

    @Test
    fun malformedJsonReturnsParseError() = runTest {
        val dispatcher = dispatcher()
        val response = dispatcher.handle(session(), "{not json")!!.jsonObject
        assertEquals(JsonRpcErrorCode.PARSE_ERROR, errorCode(response))
    }

    @Test
    fun notificationHasNoResponse() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)
        val response = dispatcher.handle(
            session,
            """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
        assertNull(response)
    }

    @Test
    fun batchReturnsArrayOfResponses() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(
            session,
            """[{"jsonrpc":"2.0","id":10,"method":"ping"},{"jsonrpc":"2.0","id":11,"method":"ping"}]"""
        )
        assertIs<JsonArray>(response)
        assertEquals(2, response.size)
    }

    @Test
    fun toolTextIsProperlyEscaped() = runTest {
        val executor = FakeExecutor(ToolResult("quote: \"x\"\nline 2"))
        val dispatcher = dispatcher(executor)
        val session = initializedSession(dispatcher)

        val response = dispatcher.handle(
            session,
            """{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/tmp/a.txt"}}}"""
        )!!.toString()

        val parsed = JsonRpc.json.parseToJsonElement(response).jsonObject
        val text = parsed["result"]!!.jsonObject["content"]!!.jsonArray.first()
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("quote: \"x\"\nline 2", text)
    }

    @Test
    fun pingWorks() = runTest {
        val dispatcher = dispatcher()
        val session = initializedSession(dispatcher)
        val response = dispatcher.handle(session, requestJson(13, "ping"))!!.jsonObject
        assertNotNull(response["result"])
        assertNull(response["error"])
    }

    private fun dispatcher(executor: ToolExecutor = FakeExecutor(ToolResult("ok"))): McpDispatcher =
        McpDispatcher(
            tools = McpTools.available(),
            executor = executor
        )

    private fun session(): McpSession = McpSession(
        McpClient(
            id = "test-client",
            name = "Test",
            remoteAddress = "127.0.0.1",
            transport = Transport.STREAMABLE_HTTP,
            connectedAt = 0L
        )
    )

    private suspend fun initializedSession(dispatcher: McpDispatcher): McpSession {
        val session = session()
        dispatcher.handle(session, initializeJson("2025-06-18"))
        return session
    }

    private fun errorCode(response: JsonObject): Int =
        response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    private fun initializeJson(version: String): String =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""

    private fun requestJson(id: Int, method: String): String =
        """{"jsonrpc":"2.0","id":$id,"method":"$method"}"""

    private class FakeExecutor(
        private val result: ToolResult
    ) : ToolExecutor {
        var lastClientId: String? = null
        var lastTool: String? = null
        var lastArguments: JsonObject? = null

        override suspend fun execute(clientId: String, toolName: String, arguments: JsonObject): ToolResult {
            lastClientId = clientId
            lastTool = toolName
            lastArguments = arguments
            return result
        }
    }
}
