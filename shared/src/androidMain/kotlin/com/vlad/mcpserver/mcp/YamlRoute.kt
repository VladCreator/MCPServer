package com.vlad.mcpserver.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import java.io.StringReader
import java.util.UUID
import kotlin.time.TimeSource

internal data class RouteStep(val id: String, val tool: String, val arguments: JsonObject, val phase: String = "steps")
internal data class YamlRoute(
    val id: String,
    val packageName: String,
    val timeoutMs: Int,
    val steps: List<RouteStep>,
    val schemaVersion: Int = 1,
    val version: String? = null,
    val cleanup: List<RouteStep> = emptyList(),
    val cleanupTimeoutMs: Int = 10000,
    val catalogDigest: String? = null
) {
    val allSteps: List<RouteStep> get() = steps + cleanup
    val changesState: Boolean get() = steps.any { it.tool == "device_state" }
    fun plan(): JsonObject = buildJsonObject {
        put("route_id", id); put("schema_version", schemaVersion)
        version?.let { put("version", it) }
        catalogDigest?.let { put("catalog_digest", it) }
        put("app_id", packageName); put("timeout_ms", timeoutMs); put("cleanup_timeout_ms", cleanupTimeoutMs)
        put("steps", JsonArray(allSteps.map { step -> buildJsonObject {
            put("id", step.id); put("phase", step.phase); put("tool", step.tool); put("arguments", step.arguments)
        } }))
    }
}

/** Deliberately small YAML dialect: data only, no tags, aliases or executable expressions. */
internal object YamlRouteParser {
    private val name = Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val reference = Regex("""\$\{([A-Za-z][A-Za-z0-9_.-]{0,79})\}""")
    private val selectors = setOf("text", "resource_id", "content_desc", "class_name", "text_exact", "within", "visibility")

    fun parse(source: String, supplied: JsonObject = JsonObject(emptyMap())): YamlRoute {
        require(source.length in 1..32768) { "Route YAML must contain 1..32768 characters." }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 12
            codePointLimit = 32768
        }
        val yaml = Yaml(SafeConstructor(options))
        val root = try {
            require(yaml.parse(StringReader(source)).none {
                it is AliasEvent || (it is NodeEvent && it.anchor != null) ||
                    (it is ScalarEvent && it.tag != null) || (it is CollectionStartEvent && it.tag != null)
            }) { "YAML anchors, aliases and explicit tags are not supported." }
            toJson(yaml.load<Any?>(source)).obj("Route")
        } catch (_: YAMLException) {
            throw IllegalArgumentException("Invalid YAML: use one document with unique keys and supported scalar types.")
        }
        return compile(root, supplied)
    }

    fun parse(root: JsonObject, supplied: JsonObject = JsonObject(emptyMap())): YamlRoute {
        require(root.toString().length <= 32768) { "Scenario exceeds 32768 characters." }
        return compile(root, supplied)
    }

    private fun compile(root: JsonObject, supplied: JsonObject): YamlRoute {
        fun depth(value: JsonElement, level: Int = 0) {
            require(level <= 12) { "Scenario exceeds 12 nesting levels." }
            when (value) {
                is JsonObject -> value.values.forEach { depth(it, level + 1) }
                is JsonArray -> value.forEach { depth(it, level + 1) }
                else -> Unit
            }
        }
        depth(root)
        val schema = root.integer("schema_version", 1..2)
        val v2 = setOf("version", "preconditions", "setup", "postconditions", "cleanup", "cleanup_timeout_ms")
        root.keysOnly(setOf("schema_version", "id", "platform", "app_id", "timeout_ms", "parameters", "steps") +
            if (schema == 2) v2 else emptySet())
        val version = if (schema == 2) root.string("version").also {
            require(Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}").matches(it)) { "version must be numeric major.minor.patch." }
        } else null
        val id = root.string("id")
        require(name.matches(id)) { "Invalid route id." }
        require(root.string("platform") == "android") { "Only platform: android is supported." }
        val definitions = root["parameters"]?.obj("parameters") ?: JsonObject(emptyMap())
        require(definitions.size <= 20) { "At most 20 parameters are allowed." }
        require(supplied.keys.all { it in definitions }) { "Undeclared route parameter." }
        val parameters = definitions.mapValues { (key, raw) ->
            require(name.matches(key)) { "Invalid parameter name." }
            val spec = raw.obj("Parameter")
            spec.keysOnly(setOf("type", "default"))
            val type = spec.string("type")
            require(type in setOf("string", "integer", "boolean")) { "Unsupported parameter type." }
            val value = supplied[key] ?: spec["default"]
            require(value is JsonPrimitive && value !is JsonNull && when (type) {
                "string" -> value.isString && value.content.length <= 5000
                "integer" -> !value.isString && value.intOrNull != null
                else -> !value.isString && value.booleanOrNull != null
            }) { "Missing or incorrectly typed parameter: $key." }
            value
        }
        val resolved = bind(root, parameters).obj("Route")
        val app = resolved.string("app_id")
        require(packageName.matches(app)) { "Invalid app_id." }
        val timeout = if ("timeout_ms" in resolved) resolved.integer("timeout_ms", 1..60000) else 60000
        val steps = resolved["steps"] as? JsonArray ?: throw IllegalArgumentException("steps must be a list.")
        require(steps.size in 1..50) { "A route must have 1..50 steps." }
        fun phase(key: String): List<RouteStep> {
            val entries = if (key == "steps") steps else resolved[key]?.let {
                it as? JsonArray ?: throw IllegalArgumentException("$key must be a list.")
            } ?: JsonArray(emptyList())
            return entries.mapIndexed { index, raw ->
                val step = compileStep(index, raw.obj("Step"), app, schema == 2 && key == "setup")
                if (key in setOf("preconditions", "postconditions")) require(step.tool in setOf("ui_wait", "ui_assert")) {
                    "$key accepts observation checks only."
                }
                step.copy(id = if (key == "steps") step.id else "${key}_${index + 1}", phase = key)
            }
        }
        val main = listOf("setup", "preconditions", "steps", "postconditions").flatMap(::phase)
        val cleanup = phase("cleanup").toMutableList()
        if (main.any { it.tool == "device_state" })
            cleanup += RouteStep("cleanup_state", "restore_state", JsonObject(emptyMap()), "cleanup")
        require(main.size + cleanup.size <= 50) { "At most 50 total scenario steps including automatic restoration." }
        val cleanupTimeout = if ("cleanup_timeout_ms" in resolved)
            resolved.integer("cleanup_timeout_ms", 1000..15000) else 10000
        require(main.none { it.tool == "device_state" } || cleanupTimeout >= 6000) {
            "State-changing scenarios need cleanup_timeout_ms >= 6000 (5000 reserved for restoration)."
        }
        return YamlRoute(id, app, timeout, main, schema, version, cleanup, cleanupTimeout)
    }

    private fun compileStep(index: Int, step: JsonObject, app: String, setup: Boolean = false): RouteStep {
        require(step.size == 1) { "Each step must contain exactly one command." }
        val (command, raw) = step.entries.single()
        val args = raw.obj("Step arguments")
        val tool = when (command) {
            "tap" -> "click_element"
            "back" -> "global_action"
            "wait" -> "ui_wait"
            "assert" -> "ui_assert"
            "device_state", "open_link" -> {
                require(setup) { "Environment commands are allowed only in schema 2 setup." }
                command
            }
            "launch_app", "input_text", "scroll", "delay" -> command
            else -> throw IllegalArgumentException("Unsupported route command.")
        }
        val allowed = when (command) {
            "tap" -> selectors + "long_press"
            "input_text" -> selectors + setOf("match_text")
            "scroll" -> selectors + setOf("direction")
            "wait", "assert" -> selectors + setOf("condition", "expected", "timeout_ms")
            "delay" -> setOf("milliseconds")
            "device_state" -> setOf("rotation", "font_scale", "animations", "volume", "keep_awake")
            "open_link" -> setOf("uri", "wait_ms")
            else -> emptySet()
        }
        args.keysOnly(allowed)
        args.forEach { (key, value) ->
            when (key) {
                "text_exact", "long_press", "animations", "keep_awake" -> require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
                    "$key must be a boolean."
                }
                "timeout_ms" -> args.integer(key, 250..30000)
                "milliseconds" -> args.integer(key, 0..5000)
                "wait_ms" -> args.integer(key, 250..30000)
                "volume" -> args.integer(key, 0..100)
                "font_scale" -> require(value is JsonPrimitive && !value.isString &&
                    value.content.toFloatOrNull()?.let { it.isFinite() && it in .85f..2f } == true) {
                    "font_scale must be 0.85-2.0."
                }
                "rotation" -> require(args.string(key) in setOf("auto", "portrait", "landscape", "reverse_portrait", "reverse_landscape")) {
                    "Invalid rotation."
                }
                "within" -> {
                    val container = value.obj("within")
                    container.keysOnly(setOf("text", "text_exact", "resource_id", "content_desc", "class_name"))
                    require(container.keys.any { it != "text_exact" }) { "within requires a container identity." }
                    container.forEach { (field, entry) ->
                        if (field == "text_exact") require(entry is JsonPrimitive && !entry.isString &&
                            entry.booleanOrNull != null) { "text_exact must be a boolean." }
                        else container.string(field)
                    }
                }
                "visibility" -> require(args.string(key) in setOf("accessibility", "intersecting", "fully_visible")) {
                    "Invalid visibility policy."
                }
                else -> args.string(key, allowEmpty = (key == "text" && command == "input_text") ||
                    (key == "expected" && command in setOf("wait", "assert")))
            }
        }
        fun requireSelector() {
            require(args.keys.any { it in selectors - setOf("text_exact", "visibility") }) { "$command requires a selector." }
        }
        when (command) {
            "device_state" -> require(args.isNotEmpty()) { "device_state requires at least one field." }
            "open_link" -> AppLink.validate(args.string("uri"))
            "tap" -> requireSelector()
            "input_text" -> {
                args.string("text", allowEmpty = true)
                require(args.keys.any { it in setOf("resource_id", "content_desc", "match_text", "class_name", "within") }) {
                    "input_text requires an explicit field selector."
                }
            }
            "scroll" -> require(args.string("direction") in McpTools.SCROLL_DIRECTIONS) { "Invalid scroll direction." }
            "delay" -> args.integer("milliseconds", 0..5000)
            "wait", "assert" -> {
                val condition = args.string("condition")
                require(condition in if (command == "wait") setOf("visible", "absent", "text", "idle")
                    else setOf("visible", "absent", "text", "count")) { "Unsupported condition." }
                if (condition != "idle") requireSelector()
                if (condition == "count") {
                    require(args.string("expected").toIntOrNull()?.let { it in 0..99 } == true) {
                        "count requires expected to be a quoted integer in 0..99 (native matching caps at 100)."
                    }
                }
                if (condition == "text") {
                    require("expected" in args || "text" in args) { "text condition needs expected or text." }
                }
            }
        }
        val compiled = buildJsonObject {
            args.forEach { (key, value) -> put(key, value) }
            when (command) {
                "launch_app" -> { put("package_name", app); put("wait_ms", 0) }
                "open_link" -> put("package_name", app)
                "device_state" -> put("action", "set")
                "back" -> put("action", "back")
                "wait" -> if (args["condition"] == JsonPrimitive("idle")) put("package_name", app)
            }
        }
        return RouteStep("step_${index + 1}", tool, compiled)
    }

    private fun bind(value: JsonElement, parameters: Map<String, JsonPrimitive>): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (key == "parameters") child else bind(child, parameters)
        })
        is JsonArray -> JsonArray(value.map { bind(it, parameters) })
        is JsonPrimitive -> if (value.isString && value.content.contains("\${")) {
            val match = reference.matchEntire(value.content)
            require(match != null) { "Parameter references must occupy the entire scalar." }
            parameters[match.groupValues[1]] ?: throw IllegalArgumentException("Unknown parameter reference.")
        } else value
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::toJson))
        is Map<*, *> -> {
            require(value.keys.all { it is String }) { "YAML keys must be strings." }
            JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
        }
        else -> throw IllegalArgumentException("Unsupported YAML scalar type; quote text values.")
    }

    private fun JsonElement.obj(label: String) = this as? JsonObject
        ?: throw IllegalArgumentException("$label must be a mapping.")

    private fun JsonObject.keysOnly(allowed: Set<String>) {
        require(keys.all { it in allowed }) { "Unknown field in route, parameter or step." }
    }

    private fun JsonObject.string(key: String, allowEmpty: Boolean = false): String {
        val value = this[key] as? JsonPrimitive
        require(value != null && value.isString && value.content.length <= 5000 &&
            (allowEmpty || value.content.isNotBlank())) { "$key must be ${if (allowEmpty) "a" else "a nonempty"} string (max 5000 characters)." }
        return value.content
    }

    private fun JsonObject.integer(key: String, range: IntRange): Int {
        val value = this[key] as? JsonPrimitive
        val number = value?.takeUnless { it.isString }?.intOrNull
        require(number != null && number in range) { "$key must be an integer in ${range.first}..${range.last}." }
        return number
    }
}

internal class YamlRouteRunner {
    suspend fun run(
        route: YamlRoute,
        runId: String = UUID.randomUUID().toString(),
        onStep: (JsonObject) -> Unit = {},
        execute: suspend (RouteStep) -> ToolResult
    ): ToolResult {
        if (route.schemaVersion == 1) return runSteps(route, runId, onStep, execute)
        val start = TimeSource.Monotonic.markNow()
        val records = route.allSteps.associate { step -> step.id to buildJsonObject {
            put("id", step.id); put("tool", step.tool); put("phase", step.phase); put("status", "pending")
        } }.toMutableMap()
        fun record(step: JsonObject) {
            val id = step.getValue("id").jsonPrimitive.content
            val previous = records.getValue(id)
            val timed = JsonObject(step + ("offset_ms" to (previous["offset_ms"]
                ?: JsonPrimitive(start.elapsedNow().inWholeMilliseconds))))
            records[id] = timed
            onStep(timed)
        }
        var primary: ToolResult? = null
        var failure: Exception? = null
        var primaryStatus = "failed"
        var cleanupFailed = false
        var restorePending = false
        fun cleanupRecord(step: JsonObject) {
            try { record(step) } catch (error: Exception) { failure = failure ?: error }
        }
        try {
            primary = runSteps(route, runId, ::record, execute)
            primaryStatus = Json.parseToJsonElement(primary.text).jsonObject.getValue("status").jsonPrimitive.content
        } catch (_: CancellationException) {
            primaryStatus = "cancelled"
        } catch (error: Exception) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                for (step in route.steps) {
                    val previous = records.getValue(step.id)
                    if (previous.getValue("status").jsonPrimitive.content in setOf("pending", "dispatched")) {
                        cleanupRecord(JsonObject(previous + ("status" to JsonPrimitive(
                            if (previous.getValue("status") == JsonPrimitive("dispatched")) "interrupted" else "skipped"))))
                    }
                }
                val automatic = route.cleanup.filter { it.tool == "restore_state" }
                val userSteps = route.cleanup.filter { it.tool != "restore_state" }
                val budget = route.cleanupTimeoutMs - if (automatic.isNotEmpty()) 5000 else 0
                val finished = withTimeoutOrNull(budget.toLong()) {
                    for (step in userSteps) {
                        if (failure != null) { cleanupFailed = true; break }
                        try {
                            val output = runSteps(route.copy(steps = listOf(step), timeoutMs = budget), runId, ::record, execute)
                            if (output.isError) cleanupFailed = true
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failure = failure ?: error; cleanupFailed = true }
                    }
                    true
                } == true
                if (!finished) cleanupFailed = true
                for (step in userSteps) {
                    val previous = records.getValue(step.id)
                    if (previous.getValue("status").jsonPrimitive.content in setOf("pending", "dispatched")) {
                        cleanupRecord(JsonObject(previous + ("status" to JsonPrimitive(
                            if (previous.getValue("status") == JsonPrimitive("dispatched")) "timed_out" else "skipped"))))
                    }
                }
                // Restoration is attempted even when user cleanup failed or exhausted its budget.
                for (step in automatic) {
                    val output = runSteps(route.copy(steps = listOf(step), timeoutMs = 5000), runId, ::cleanupRecord, execute)
                    if (output.isError) { cleanupFailed = true; restorePending = true }
                }
            }
        }
        failure?.let { throw it }
        val status = if (primaryStatus == "completed" && cleanupFailed) "failed" else primaryStatus
        val report = buildJsonObject {
            put("schema_version", 2); put("run_id", runId); put("route_id", route.id)
            put("version", route.version); put("execution_location", "android_device")
            put("status", status); put("primary_status", primaryStatus)
            put("cleanup_status", if (cleanupFailed) "failed" else "completed")
            put("pending_cleanup", cleanupFailed); put("pending_state_restore", restorePending)
            put("duration_ms", start.elapsedNow().inWholeMilliseconds)
            put("steps", JsonArray(route.allSteps.map { records.getValue(it.id) }))
        }
        return ToolResult(report.toString(), isError = status != "completed", structuredContent = report,
            imageBase64 = primary?.imageBase64, imageMimeType = primary?.imageMimeType)
    }

    private suspend fun runSteps(
        route: YamlRoute,
        runId: String = UUID.randomUUID().toString(),
        onStep: (JsonObject) -> Unit = {},
        execute: suspend (RouteStep) -> ToolResult
    ): ToolResult {
        val startedAt = System.currentTimeMillis()
        val start = TimeSource.Monotonic.markNow()
        val records = mutableListOf<JsonObject>()
        var failed = false
        var image: ToolResult? = null
        var activeStartedAt = startedAt
        var activeOffsetMs = 0L
        val completed = withTimeoutOrNull(route.timeoutMs.toLong()) {
            for (step in route.steps) {
                currentCoroutineContext().ensureActive()
                if (failed) break
                val stepStart = TimeSource.Monotonic.markNow()
                val stepWallTime = System.currentTimeMillis()
                activeStartedAt = stepWallTime
                activeOffsetMs = start.elapsedNow().inWholeMilliseconds
                onStep(buildJsonObject {
                    put("id", step.id); put("tool", step.tool); put("status", "dispatched")
                    put("phase", step.phase)
                    put("started_at_ms", stepWallTime)
                })
                val result = try {
                    if (step.tool == "delay") {
                        delay(step.arguments.getValue("milliseconds").jsonPrimitive.long)
                        ToolResult("Delay completed.")
                    } else execute(step)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    ToolResult("Step execution raised ${error::class.simpleName}; no subsequent steps were executed.", isError = true)
                }
                currentCoroutineContext().ensureActive()
                if (start.elapsedNow().inWholeMilliseconds >= route.timeoutMs) {
                    return@withTimeoutOrNull false
                }
                failed = result.isError
                if (result.imageBase64 != null) image = result
                val record = buildJsonObject {
                    put("id", step.id)
                    put("tool", step.tool)
                    put("phase", step.phase)
                    put("status", if (failed) "failed" else "completed")
                    put("started_at_ms", stepWallTime)
                    put("duration_ms", stepStart.elapsedNow().inWholeMilliseconds)
                    if (step.tool == "delay") put("requested_delay_ms", step.arguments.getValue("milliseconds"))
                    put("output", result.text.take(8000))
                    result.structuredContent?.let { put("result", it) }
                }
                records += record
                onStep(record)
            }
            true
        } == true
        if (!completed) failed = true
        val finishedCount = records.size
        route.steps.drop(finishedCount).forEachIndexed { index, step ->
            val record = buildJsonObject {
                put("id", step.id)
                put("tool", step.tool)
                put("phase", step.phase)
                put("status", if (!completed && index == 0) "timed_out" else "skipped")
                if (!completed && index == 0) {
                    put("started_at_ms", activeStartedAt)
                    put("duration_ms", start.elapsedNow().inWholeMilliseconds - activeOffsetMs)
                    if (step.tool == "delay") put("requested_delay_ms", step.arguments.getValue("milliseconds"))
                }
            }
            records += record
            onStep(record)
        }
        return ToolResult(
            text = buildJsonObject {
                put("schema_version", 1)
                put("run_id", runId)
                put("route_id", route.id)
                put("execution_location", "android_device")
                put("status", if (!completed) "timed_out" else if (failed) "failed" else "completed")
                put("started_at_ms", startedAt)
                put("duration_ms", start.elapsedNow().inWholeMilliseconds)
                put("steps", JsonArray(records))
            }.toString(),
            isError = failed,
            imageBase64 = image?.imageBase64,
            imageMimeType = image?.imageMimeType
        )
    }
}
