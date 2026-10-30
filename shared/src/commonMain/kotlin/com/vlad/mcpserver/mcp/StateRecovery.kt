package com.vlad.mcpserver.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Callers serialize access; save must durably complete before write can run. */
internal class StateRecovery(
    private val load: () -> JsonObject?,
    private val save: (JsonObject?) -> Unit,
    private val preflight: (String) -> Unit,
    private val read: (String) -> JsonElement,
    private val writeAndVerify: suspend (String, JsonElement) -> Unit,
    private val logFailure: (String, Exception) -> Unit
) {
    fun pending(): Boolean = load() != null

    fun status(owner: String): ToolResult {
        val state = load()
        return result(if (state == null) "no_snapshot" else if (state.stringValue("owner") == owner)
            "pending_restore" else "state_owned_by_another_client", state != null && state.stringValue("owner") != owner,
            state?.takeIf { it.stringValue("owner") == owner }?.getValue("fields")?.jsonObject?.keys.orEmpty())
    }

    suspend fun set(owner: String, values: JsonObject): ToolResult {
        require(values.isNotEmpty()) { "Provide at least one device-state field." }
        val previous = load()
        if (previous != null && previous.stringValue("owner") != owner)
            return result("state_owned_by_another_client", true)
        // Check the whole request before snapshots or platform writes.
        values.keys.forEach(preflight)
        val originals = previous?.getValue("fields")?.jsonObject.orEmpty().toMutableMap()
        for (field in values.keys) if (field !in originals) originals[field] = read(field)
        val snapshot = buildJsonObject { put("owner", owner); put("fields", JsonObject(originals)) }
        save(snapshot)
        try {
            for ((field, value) in values) writeAndVerify(field, value)
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            cleanup(owner)
            throw cancelled
        } catch (error: Exception) {
            logFailure("Device state write/readback failed", error)
            val restored = cleanup(owner)
            return result(if (restored?.isError == false) "set_failed_restored" else "set_failed_cleanup_pending",
                true, load()?.getValue("fields")?.jsonObject?.keys.orEmpty())
        }
        val outcome = result("applied", false, originals.keys)
        val data = JsonObject(outcome.structuredContent!! + buildJsonObject {
            put("verified_fields", JsonArray(values.keys.map { JsonPrimitive(it) }))
            put("readback", JsonObject(values.keys.associateWith(read)))
        })
        return outcome.copy(text = data.toString(), structuredContent = data)
    }

    suspend fun restore(owner: String): ToolResult {
        var state = load() ?: return result("no_snapshot", true)
        if (state.stringValue("owner") != owner) return result("state_owned_by_another_client", true)
        val failed = mutableSetOf<String>()
        for ((field, original) in state.getValue("fields").jsonObject) {
            try {
                preflight(field)
                writeAndVerify(field, original)
                val remaining = state.getValue("fields").jsonObject - field
                val next = if (remaining.isEmpty()) null else JsonObject(state + ("fields" to JsonObject(remaining)))
                save(next)
                if (next != null) state = next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                logFailure("Unable to restore device field $field", error)
                failed += field
            }
        }
        return result(if (failed.isEmpty()) "restored" else "partial_restore", failed.isNotEmpty(), failed)
    }

    private suspend fun cleanup(owner: String): ToolResult? = try {
        withContext(NonCancellable) { withTimeoutOrNull(5_000) { restore(owner) } }
    } catch (error: Exception) {
        logFailure("Device cleanup failed; durable recovery remains required", error)
        null
    }

    private fun result(reason: String, error: Boolean, fields: Set<String> = emptySet()): ToolResult {
        val data = buildJsonObject {
            put("reason", reason); put("snapshot_retained", pending())
            put("pending_cleanup", pending()); put("retryable", pending())
            putJsonArray("fields") { fields.forEach { add(it) } }
        }
        return ToolResult(data.toString(), isError = error, structuredContent = data)
    }

    private fun JsonObject.stringValue(name: String) = getValue(name).jsonPrimitive.content
}
