package com.vlad.mcpserver.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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

internal data class RouteStep(val id: String, val tool: String, val arguments: JsonObject)
internal data class YamlRoute(
    val id: String,
    val packageName: String,
    val timeoutMs: Int,
    val steps: List<RouteStep>
)

/** Deliberately small YAML dialect: data only, no tags, aliases or executable expressions. */
internal object YamlRouteParser {
    private val name = Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val reference = Regex("""\$\{([A-Za-z][A-Za-z0-9_.-]{0,79})}""")
    private val selectors = setOf("text", "resource_id", "content_desc", "class_name", "text_exact")

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
        root.keysOnly(setOf("schema_version", "id", "platform", "app_id", "timeout_ms", "parameters", "steps"))
        require(root.integer("schema_version", 1..1) == 1)
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
        return YamlRoute(id, app, timeout, steps.mapIndexed { index, raw ->
            compileStep(index, raw.obj("Step"), app)
        })
    }

    private fun compileStep(index: Int, step: JsonObject, app: String): RouteStep {
        require(step.size == 1) { "Each step must contain exactly one command." }
        val (command, raw) = step.entries.single()
        val args = raw.obj("Step arguments")
        val tool = when (command) {
            "tap" -> "click_element"
            "back" -> "global_action"
            "wait" -> "ui_wait"
            "assert" -> "ui_assert"
            "launch_app", "input_text", "scroll", "delay" -> command
            else -> throw IllegalArgumentException("Unsupported route command.")
        }
        val allowed = when (command) {
            "tap" -> selectors + "long_press"
            "input_text" -> setOf("resource_id", "content_desc", "match_text", "text")
            "scroll" -> setOf("direction", "text", "resource_id", "content_desc")
            "wait", "assert" -> selectors + setOf("condition", "expected", "timeout_ms")
            "delay" -> setOf("milliseconds")
            else -> emptySet()
        }
        args.keysOnly(allowed)
        args.forEach { (key, value) ->
            when (key) {
                "text_exact", "long_press" -> require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
                    "$key must be a boolean."
                }
                "timeout_ms" -> args.integer(key, 250..30000)
                "milliseconds" -> args.integer(key, 0..5000)
                else -> args.string(key, allowEmpty = key == "text" && command == "input_text")
            }
        }
        fun requireSelector() {
            require(args.keys.any { it in selectors - "text_exact" }) { "$command requires a selector." }
        }
        when (command) {
            "tap" -> requireSelector()
            "input_text" -> {
                args.string("text", allowEmpty = true)
                require(args.keys.any { it in setOf("resource_id", "content_desc", "match_text") }) {
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
                "back" -> put("action", "back")
                "wait" -> if (args["condition"] == JsonPrimitive("idle")) put("package_name", app)
            }
            if (command in setOf("wait", "assert") && args["condition"] == JsonPrimitive("text")) {
                put("condition", "visible")
                put("text", args["expected"] ?: args.getValue("text"))
                put("text_exact", true)
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
        execute: suspend (RouteStep) -> ToolResult
    ): ToolResult {
        val runId = UUID.randomUUID().toString()
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
                records += buildJsonObject {
                    put("id", step.id)
                    put("tool", step.tool)
                    put("status", if (failed) "failed" else "completed")
                    put("started_at_ms", stepWallTime)
                    put("duration_ms", stepStart.elapsedNow().inWholeMilliseconds)
                    if (step.tool == "delay") put("requested_delay_ms", step.arguments.getValue("milliseconds"))
                    put("output", result.text.take(8000))
                }
            }
            true
        } == true
        if (!completed) failed = true
        val finishedCount = records.size
        route.steps.drop(finishedCount).forEachIndexed { index, step ->
            records += buildJsonObject {
                put("id", step.id)
                put("tool", step.tool)
                put("status", if (!completed && index == 0) "timed_out" else "skipped")
                if (!completed && index == 0) {
                    put("started_at_ms", activeStartedAt)
                    put("duration_ms", start.elapsedNow().inWholeMilliseconds - activeOffsetMs)
                    if (step.tool == "delay") put("requested_delay_ms", step.arguments.getValue("milliseconds"))
                }
            }
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
