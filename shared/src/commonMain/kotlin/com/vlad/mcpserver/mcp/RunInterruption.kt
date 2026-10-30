package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.*

internal object RunInterruption {
    fun apply(run: JsonObject, status: String, reason: String): JsonObject = JsonObject(run + buildJsonObject {
        val dispatched = run["active_step"] != null && run["active_step"] !is JsonNull
        put("status", status); put("reason", reason)
        put("action_outcome_unknown", dispatched)
        // Even a completed UI step may have changed app state; recovery is never automatic.
        put("pending_cleanup", dispatched || run.getValue("steps").jsonArray.any {
            it.jsonObject.stringValue("status") == "completed" &&
                it.jsonObject.stringValue("tool") !in setOf("delay", "ui_wait", "ui_assert")
        })
        put("steps", JsonArray(run.getValue("steps").jsonArray.map { raw ->
            val step = raw.jsonObject
            JsonObject(step + when (step.stringValue("status")) {
                "dispatched" -> mapOf("status" to JsonPrimitive("interrupted"))
                "pending" -> mapOf("status" to JsonPrimitive("skipped"))
                else -> emptyMap()
            })
        }))
    })

    private fun JsonObject.stringValue(key: String) = (this[key] as? JsonPrimitive)?.content
}
