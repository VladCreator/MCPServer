package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON-RPC 2.0 error codes used by the MCP dispatcher.
 */
object JsonRpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val SERVER_NOT_INITIALIZED = -32002
}

/**
 * Helpers to build compact JSON-RPC responses without string concatenation.
 */
object JsonRpc {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun success(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }

    fun error(
        id: JsonElement?,
        code: Int,
        message: String,
        data: JsonElement? = null
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        })
    }

    fun emptyResult(id: JsonElement?): JsonObject = success(id, buildJsonObject { })

    fun string(value: String): JsonPrimitive = JsonPrimitive(value)
}
