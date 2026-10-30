package com.vlad.mcpserver.mcp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class DurableRoutes(context: Context, private val deviceLock: Mutex) {
    val store = RunStore.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    suspend fun submit(
        route: YamlRoute,
        arguments: JsonObject,
        approve: suspend () -> Boolean,
        failureEvidence: suspend (String) -> Unit,
        execute: suspend (RouteStep, String) -> ToolResult
    ): ToolResult {
        val mode = arguments.string("mode") ?: "sync"
        require(mode in setOf("sync", "async")) { "mode must be sync or async." }
        require(arguments.string("disconnect_policy") in setOf(null, "continue_until_terminal")) {
            "Only explicit continue_until_terminal is supported."
        }
        if (mode == "async") require(arguments.string("disconnect_policy") == "continue_until_terminal") {
            "Async runs require disconnect_policy=continue_until_terminal."
        }
        val key = arguments.string("run_key")
            ?: if (mode == "sync") UUID.randomUUID().toString()
            else throw IllegalArgumentException("Async runs require a caller-generated run_key.")
        val submission = arguments.string("submission_id")
            ?: if (mode == "sync") UUID.randomUUID().toString()
            else throw IllegalArgumentException("Async runs require submission_id.")
        val queueMs = (arguments["queue_timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 10_000
        require(queueMs in 250..30_000) { "queue_timeout_ms must be 250-30000." }
        val digest = RunStore.planDigest(key, RunPrivacy.canonical(buildJsonObject {
            put("plan", route.plan()); put("queue_timeout_ms", queueMs)
            put("privacy_sensitive", arguments["privacy_sensitive"] ?: JsonPrimitive(true))
        }))
        val (admitted, fresh) = store.admit(key, submission, digest, route.allSteps,
            arguments["privacy_sensitive"] != JsonPrimitive(false), route.packageName, buildJsonObject {
                put("route_id", if (route.catalogDigest == null) "inline" else route.id)
                put("schema_version", route.schemaVersion)
                route.version?.let { put("route_version", it) }
                route.catalogDigest?.let { put("catalog_digest", it) }
                put("cleanup_status", if (route.schemaVersion == 2) "pending" else "not_configured")
            })
        val id = admitted.string("run_id")!!
        if (!fresh) return response(admitted, key, true)
        try {
            if (!approve()) {
                val denied = transition(id, "rejected", "consent_denied", markUnknown = true)
                return response(denied, key)
            }
        } catch (cancelled: CancellationException) {
            transition(id, "cancelled", "approval_cancelled", markUnknown = true)
            throw cancelled
        } catch (error: Exception) {
            transition(id, "rejected", "approval_failed", markUnknown = true)
            throw error
        }
        val afterApproval = store.update(id) { run ->
            if (run.string("status") == "awaiting_approval") JsonObject(run + ("status" to JsonPrimitive("queued")))
            else run
        }
        if (afterApproval.string("status") != "queued") return response(afterApproval, key)
        val task = scope.async(start = CoroutineStart.LAZY) {
            var acquired = false
            try {
                val result = withTimeoutOrNull(queueMs) {
                    deviceLock.lock()
                    acquired = true
                    true
                }
                if (result != true) return@async response(transition(id, "queue_timed_out", "device_busy", markUnknown = true), key)
                try {
                    currentCoroutineContext().ensureActive()
                    val running = store.update(id) { run ->
                        if (run.string("status") == "queued") JsonObject(run + ("status" to JsonPrimitive("running")))
                        else run
                    }
                    if (running.string("status") != "running") return@async response(running, key)
                    val output = YamlRouteRunner().run(route, id, onStep = { step -> recordStep(id, step) }) { step ->
                        execute(step, key)
                    }
                    val report = Json.parseToJsonElement(output.text).jsonObject
                    store.update(id) { run -> JsonObject(run + buildJsonObject {
                        put("status", report.getValue("status")); put("duration_ms", report.getValue("duration_ms"))
                        put("active_step", JsonNull); put("completed_at_ms", System.currentTimeMillis())
                        for (field in listOf("primary_status", "cleanup_status", "pending_cleanup", "pending_state_restore"))
                            report[field]?.let { put(field, it) }
                        if (output.isError) put("evidence_status", "pending")
                    }) }
                    if (output.isError) {
                        try {
                            withTimeout(5_000) { failureEvidence(id) }
                        } catch (cancelled: CancellationException) {
                            if (cancelled !is TimeoutCancellationException) {
                                evidenceFailure(id, "interrupted")
                                throw cancelled
                            }
                            evidenceFailure(id, "timed_out")
                        } catch (error: Exception) {
                            android.util.Log.e("TesterRuns", "Failure evidence collection failed", error)
                            evidenceFailure(id, "failed")
                        }
                    }
                    val durable = store.update(id) { it }
                    val combined = JsonObject(report + durable + buildJsonObject {
                        put("run_key", key); put("deduplicated", false)
                    })
                    // Legacy direct response retains outputs/images; persistent data remains redacted.
                    val direct = JsonObject(combined + ("steps" to report.getValue("steps")))
                    ToolResult(direct.toString(), isError = output.isError,
                        imageBase64 = output.imageBase64, imageMimeType = output.imageMimeType,
                        structuredContent = direct)
                } finally { deviceLock.unlock(); acquired = false }
            } catch (cancelled: CancellationException) {
                val state = transition(id, "cancelled", "cancel_requested", markUnknown = true)
                response(state, key)
            } catch (error: Exception) {
                android.util.Log.e("TesterRuns", "Durable route execution failed", error)
                response(transition(id, "failed", "execution_or_persistence_failed", markUnknown = true), key)
            } finally {
                if (acquired) deviceLock.unlock()
                jobs.remove(id)
            }
        }
        jobs[id] = task
        task.invokeOnCompletion { error ->
            jobs.remove(id, task)
            if (error is CancellationException) {
                try {
                    store.update(id) { run ->
                        if (run.string("status") in RunStore.ACTIVE)
                            RunInterruption.apply(run, "cancelled", "cancel_requested")
                        else run
                    }
                } catch (failure: Exception) {
                    android.util.Log.e("TesterRuns", "Unable to persist cancelled run; recovery requires restart", failure)
                }
            }
        }
        task.start()
        if (mode == "async") return response(store.authorized(id, key)!!, key)
        // A dropped request does not replay or cancel an accepted run.
        return task.await()
    }

    fun get(arguments: JsonObject): ToolResult = response(owned(arguments), null)

    suspend fun cancel(arguments: JsonObject): ToolResult {
        val run = owned(arguments)
        val id = run.string("run_id")!!
        val job = jobs[id]
        if (run.string("status") !in RunStore.ACTIVE) return response(run, null)
        if (job == null) return response(transition(id, "cancelled", "cancel_requested", markUnknown = true), null)
        val cancelling = transition(id, "cancelling")
        if (cancelling.string("status") != "cancelling") return response(cancelling, null)
        job.cancel()
        val finished = withTimeoutOrNull(2_000) { job.join(); true } == true
        return response(store.authorized(id, arguments.string("run_key")!!)!!, null, cancellationPending = !finished)
    }

    fun stop() { jobs.values.forEach { it.cancel() } }

    fun owned(arguments: JsonObject): JsonObject {
        val id = arguments.string("run_id") ?: error("run_id is required.")
        val key = arguments.string("run_key") ?: error("run_key is required.")
        return store.authorized(id, key) ?: throw IllegalArgumentException("Run unavailable or recovery key invalid.")
    }

    private fun recordStep(id: String, step: JsonObject) {
        store.update(id) { run ->
            if (run.string("status") == "cancelling" && step.string("phase") != "cleanup" &&
                step.string("status") == "dispatched") throw CancellationException("Run cancellation requested.")
            val clean = RunPrivacy.step(step)
            val records = run.getValue("steps").jsonArray.map {
                if (it.jsonObject.string("id") == step.string("id")) clean else it
            }
            JsonObject(run + buildJsonObject {
                put("steps", JsonArray(records))
                put("active_step", if (step.string("status") == "dispatched") step.getValue("id") else JsonNull)
                if (step.string("phase") == "cleanup" && step.string("status") == "dispatched") {
                    put("cleanup_status", "running"); put("pending_cleanup", true)
                    if ("primary_status" !in run) {
                        val main = records.map { it.jsonObject }.filter { it.string("phase") != "cleanup" }
                        put("primary_status", when {
                            run.string("status") == "cancelling" -> "cancelled"
                            main.any { it.string("status") == "timed_out" } -> "timed_out"
                            main.any { it.string("status") == "failed" } -> "failed"
                            main.all { it.string("status") == "completed" } -> "completed"
                            else -> "interrupted"
                        })
                    }
                }
                if (step.string("tool") == "device_state" && step.string("status") == "dispatched") {
                    put("pending_state_restore", true); put("pending_cleanup", true)
                }
                if (step.string("tool") == "restore_state" && step.string("status") == "completed") {
                    put("pending_state_restore", false)
                }
            })
        }
    }

    private fun transition(id: String, status: String, reason: String? = null, markUnknown: Boolean = false): JsonObject =
        store.update(id) { run -> if (run.string("status") !in RunStore.ACTIVE) run else JsonObject(run + buildJsonObject {
            put("status", status); put("updated_at_ms", System.currentTimeMillis())
            reason?.let { put("reason", it) }
            if (markUnknown) RunInterruption.apply(run, status, reason ?: "interrupted")
                .forEach { (key, value) -> put(key, value) }
        }) }

    private fun evidenceFailure(id: String, status: String) {
        store.update(id) { JsonObject(it + ("evidence_status" to JsonPrimitive(status))) }
    }

    private fun response(run: JsonObject, key: String?, duplicate: Boolean = false, cancellationPending: Boolean = false): ToolResult {
        val data = JsonObject(run + buildJsonObject {
            key?.let { put("run_key", it) }
            put("deduplicated", duplicate); put("cancellation_pending", cancellationPending)
        })
        return ToolResult(data.toString(), isError = run.string("status") in setOf(
            "failed", "timed_out", "interrupted", "cancelled", "rejected", "queue_timed_out"
        ), structuredContent = data)
    }
}
