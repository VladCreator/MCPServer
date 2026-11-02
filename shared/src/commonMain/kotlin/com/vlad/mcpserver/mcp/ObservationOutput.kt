package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ObservationOutput {
    fun bound(payload: JsonObject, arrayKey: String, full: Boolean): JsonObject {
        val items = (payload[arrayKey] as JsonArray).toMutableList()
        val originalCount = items.size
        val budget = if (full) 62 * 1024 else 22 * 1024
        fun result(): JsonObject = JsonObject(payload + mapOf(
            arrayKey to JsonArray(items),
            "detail" to JsonPrimitive(if (full) "full" else "compact"),
            "returned_count" to JsonPrimitive(items.size),
            "byte_truncated" to JsonPrimitive(items.size != originalCount),
            "omitted_count" to JsonPrimitive(originalCount - items.size)
        ) + if (items.size != originalCount) mapOf("truncated" to JsonPrimitive(true)) else emptyMap())
        var bounded = result()
        while (bounded.toString().encodeToByteArray().size > budget && items.isNotEmpty()) {
            items.removeAt(items.lastIndex)
            bounded = result()
        }
        require(bounded.toString().encodeToByteArray().size <= budget) { "Observation metadata exceeds response budget." }
        return bounded
    }

    fun summary(tool: String, payload: JsonObject): String =
        "$tool: ${payload["returned_count"]} returned; truncated=${payload["truncated"]}; " +
            "unstable=${payload["unstable"]}. Read structuredContent for targets and diagnostics."
}
