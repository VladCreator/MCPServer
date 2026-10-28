package com.vlad.mcpserver.mcp

import com.vlad.mcpserver.nowMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Platform-independent MCP JSON-RPC dispatcher.
 *
 * It implements the subset of the MCP specification used by this app:
 * initialize, ping, tools/list and tools/call, plus the standard error
 * responses. Transports only need to parse/route bytes and call [handle].
 */
class McpDispatcher(
    private val tools: List<ToolDefinition> = McpTools.available(),
    private val executor: ToolExecutor,
    private val onToolCall: suspend (ToolCallRecord) -> Unit = { },
    private val platformLabel: String = "Android"
) {

    suspend fun handle(session: McpSession, payload: String): JsonElement? {
        val element = try {
            JsonRpc.json.parseToJsonElement(payload)
        } catch (error: Throwable) {
            return JsonRpc.error(null, JsonRpcErrorCode.PARSE_ERROR, "Parse error")
        }
        return handleElement(session, element)
    }

    private suspend fun handleElement(session: McpSession, element: JsonElement): JsonElement? =
        when (element) {
            is JsonArray -> handleBatch(session, element)
            is JsonObject -> handleRequest(session, element)
            else -> JsonRpc.error(null, JsonRpcErrorCode.INVALID_REQUEST, "Invalid Request")
        }

    private suspend fun handleBatch(session: McpSession, batch: JsonArray): JsonElement? {
        if (batch.isEmpty()) {
            return JsonRpc.error(null, JsonRpcErrorCode.INVALID_REQUEST, "Invalid Request: empty batch")
        }
        val responses = mutableListOf<JsonElement>()
        for (item in batch) {
            if (item !is JsonObject) {
                responses += JsonRpc.error(null, JsonRpcErrorCode.INVALID_REQUEST, "Invalid Request")
                continue
            }
            handleRequest(session, item)?.let { responses += it }
        }
        return if (responses.isEmpty()) null else JsonArray(responses)
    }

    private suspend fun handleRequest(
        session: McpSession,
        request: JsonObject
    ): JsonObject? {
        val hasId = request.containsKey("id")
        val id = request["id"]

        val jsonRpcVersion = request["jsonrpc"].asStringOrNull()
        if (jsonRpcVersion != "2.0") {
            return JsonRpc.error(id, JsonRpcErrorCode.INVALID_REQUEST, "Invalid Request: jsonrpc must be \"2.0\"")
        }

        val method = request["method"].asStringOrNull()
            ?: return JsonRpc.error(id, JsonRpcErrorCode.INVALID_REQUEST, "Invalid Request: missing method")

        val params = request["params"] as? JsonObject

        if (method.startsWith("notifications/")) {
            handleNotification(session, method)
            return null
        }

        if (!session.initialized && method != "initialize" && method != "ping") {
            return JsonRpc.error(
                id,
                JsonRpcErrorCode.SERVER_NOT_INITIALIZED,
                "Server not initialized. Send initialize first."
            )
        }

        return when (method) {
            "initialize" -> handleInitialize(session, id, params)
            "ping" -> JsonRpc.success(id, buildJsonObject { })
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(session, id, params)
            else -> {
                if (!hasId) {
                    // Unknown notifications are intentionally ignored.
                    null
                } else {
                    JsonRpc.error(
                        id,
                        JsonRpcErrorCode.METHOD_NOT_FOUND,
                        "Method not found: $method"
                    )
                }
            }
        }
    }

    private fun handleNotification(session: McpSession, method: String) {
        if (method == "notifications/initialized") {
            session.initialized = true
        }
    }

    private fun handleInitialize(
        session: McpSession,
        id: JsonElement?,
        params: JsonObject?
    ): JsonObject {
        val requested = params?.get("protocolVersion").asStringOrNull()
        val negotiated = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) {
            requested
        } else {
            LATEST_PROTOCOL_VERSION
        }
        session.initialized = true
        session.protocolVersion = negotiated

        return JsonRpc.success(
            id,
            buildJsonObject {
                put("protocolVersion", negotiated)
                putJsonObject("capabilities") {
                    putJsonObject("tools") {
                        put("listChanged", false)
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", SERVER_NAME)
                    put("version", SERVER_VERSION)
                }
                put(
                    "instructions",
                    "Local $platformLabel Tester. Use tools/list to discover the available device tools."
                )
            }
        )
    }

    private fun handleToolsList(id: JsonElement?): JsonObject {
        val available = tools.filter { it.available }
        return JsonRpc.success(
            id,
            buildJsonObject {
                putJsonArray("tools") {
                    available.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("inputSchema", tool.inputSchema)
                            }
                        )
                    }
                }
            }
        )
    }

    private suspend fun handleToolsCall(
        session: McpSession,
        id: JsonElement?,
        params: JsonObject?
    ): JsonObject {
        if (params == null) {
            return JsonRpc.error(id, JsonRpcErrorCode.INVALID_PARAMS, "Invalid params: expected object")
        }
        val toolName = params["name"].asStringOrNull()
            ?: return JsonRpc.error(id, JsonRpcErrorCode.INVALID_PARAMS, "Invalid params: missing tool name")

        val tool = tools.firstOrNull { it.available && it.name == toolName }
            ?: return JsonRpc.error(id, JsonRpcErrorCode.INVALID_PARAMS, "Unknown tool: $toolName")

        val arguments = when (val rawArguments = params["arguments"]) {
            null, is JsonNull -> buildJsonObject { }
            is JsonObject -> rawArguments
            else -> return JsonRpc.error(
                id,
                JsonRpcErrorCode.INVALID_PARAMS,
                "Invalid params: arguments must be an object"
            )
        }

        validateArguments(tool, arguments)?.let { validationError ->
            return JsonRpc.error(id, JsonRpcErrorCode.INVALID_PARAMS, validationError)
        }

        val result = try {
            executor.execute(session.client.id, toolName, arguments)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolResult(
                text = "Tool execution failed: ${error.message ?: error::class.simpleName}",
                isError = true
            )
        }

        val record = ToolCallRecord(
            clientId = session.client.id,
            toolName = toolName,
            arguments = arguments.toString().take(MAX_LOGGED_ARGUMENTS),
            outcome = if (result.isError) ToolCallOutcome.FAILED else ToolCallOutcome.SUCCESS,
            message = result.text.take(MAX_LOGGED_RESULT),
            timestamp = nowMillis()
        )
        try {
            onToolCall(record)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Preserve the tool response, but do not hide a failed history callback.
            error.printStackTrace()
        }

        return JsonRpc.success(
            id,
            buildJsonObject {
                putJsonArray("content") {
                    if (result.imageBase64 != null) {
                        add(
                            buildJsonObject {
                                put("type", "image")
                                put("data", result.imageBase64)
                                put("mimeType", result.imageMimeType ?: "image/jpeg")
                            }
                        )
                    }
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", result.text)
                        }
                    )
                }
                put("isError", result.isError)
            }
        )
    }

    private fun validateArguments(tool: ToolDefinition, arguments: JsonObject): String? {
        for (required in tool.required) {
            if (!arguments.containsKey(required)) {
                return "Invalid params: missing required argument '$required'"
            }
        }
        val properties = tool.inputSchema["properties"] as? JsonObject ?: return null
        for ((name, schema) in properties) {
            val value = arguments[name] ?: continue
            if (value is JsonNull) continue
            val expectedType = (schema as? JsonObject)
                ?.get("type").asStringOrNull()
                ?: continue
            val valid = when (expectedType) {
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString &&
                    (value.content == "true" || value.content == "false")
                "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
                "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                else -> true
            }
            if (!valid) {
                return "Invalid params: '$name' must be of type $expectedType"
            }
        }
        return null
    }

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val LATEST_PROTOCOL_VERSION = "2025-06-18"
        val SUPPORTED_PROTOCOL_VERSIONS = listOf(
            "2025-06-18",
            "2025-03-26",
            "2024-11-05"
        )
        const val SERVER_NAME = "Tester"
        const val SERVER_VERSION = "0.1.2"
        private const val MAX_LOGGED_ARGUMENTS = 2_000
        private const val MAX_LOGGED_RESULT = 2_000
    }
}
