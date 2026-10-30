package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.*

/** Persistent reports deliberately omit free text, not just recognizable secrets. */
internal object RunPrivacy {
    private val values = setOf(
        "id", "tool", "status", "reason", "condition", "execution_location", "run_id",
        "schema_version", "started_at_ms", "duration_ms", "elapsed_ms", "requested_delay_ms",
        "passed", "truncated", "retryable", "action_dispatched", "phase", "offset_ms", "pending_cleanup"
    )

    fun step(value: JsonObject): JsonObject = buildJsonObject {
        value.forEach { (key, entry) ->
            when {
                key == "result" && entry is JsonObject -> put(key, step(entry))
                key in values -> put(key, entry)
            }
        }
        put("content_redacted", true)
    }

    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") {
            JsonPrimitive(it).toString() + ":" + canonical(value.getValue(it))
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}
