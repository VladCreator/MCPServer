package com.vlad.mcpserver.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamlRouteTest {
    private fun source(steps: String, extra: String = "") = """
        schema_version: 1
        id: navigation.race
        platform: android
        app_id: com.example.projects
        $extra
        steps:
    """.trimIndent() + "\n" + steps.prependIndent("  ")

    @Test
    fun compilesAllSupportedCommandsAndBindsTypedParameters() {
        val route = YamlRouteParser.parse(source(
            """
            - launch_app: {}
            - wait: {condition: idle, timeout_ms: 2000}
            - tap: {resource_id: open, long_press: false}
            - delay: {milliseconds: "${'$'}{pause}"}
            - back: {}
            - input_text: {resource_id: search, text: "${'$'}{query}"}
            - scroll: {direction: down}
            - assert: {condition: visible, text: Projects}
            """.trimIndent(),
            "parameters: {pause: {type: integer, default: 100}, query: {type: string}}"
        ), buildJsonObject { put("query", "Road works") })
        assertEquals(60000, route.timeoutMs)
        assertEquals(listOf("launch_app", "ui_wait", "click_element", "delay", "global_action",
            "input_text", "scroll", "ui_assert"), route.steps.map { it.tool })
        assertEquals(0, route.steps[0].arguments.getValue("wait_ms").jsonPrimitive.int)
        assertEquals("com.example.projects", route.steps[1].arguments.getValue("package_name").jsonPrimitive.content)
        assertEquals(100, route.steps[3].arguments.getValue("milliseconds").jsonPrimitive.int)
        assertEquals("Road works", route.steps[5].arguments.getValue("text").jsonPrimitive.content)
        assertEquals("step_8", route.steps.last().id)
    }

    @Test
    fun textChecksUseTheNativePredicateWithoutSelectorRewriting() {
        val route = YamlRouteParser.parse(source(
            """- assert: {condition: text, resource_id: title, expected: Projects}"""
        ))
        val args = route.steps.single().arguments
        assertEquals("text", args.getValue("condition").jsonPrimitive.content)
        assertEquals("Projects", args.getValue("expected").jsonPrimitive.content)
        assertFalse("text" in args)
        assertEquals("title", args.getValue("resource_id").jsonPrimitive.content)
    }

    @Test
    fun routesPreserveNativeTextFailureAndStructuredOutcome() = runTest {
        val route = YamlRouteParser.parse(source(
            "- assert: {condition: text, resource_id: title, expected: Wrong}\n- back: {}"
        ))
        var calls = 0
        val result = YamlRouteRunner().run(route) { step ->
            calls++
            val outcome = UiPredicate.evaluate("text", listOf("Projects"),
                step.arguments.getValue("expected").jsonPrimitive.content, true, "app", "app")
            ToolResult("Actual: Projects", isError = !outcome.passed,
                structuredContent = outcome.structured("text", "Wrong", 10))
        }
        assertTrue(result.isError)
        assertEquals(1, calls)
        assertEquals(listOf("failed", "skipped"), statuses(result))
        val record = Json.parseToJsonElement(result.text).jsonObject.getValue("steps").jsonArray.first().jsonObject
        assertEquals("text_mismatch", record.getValue("result").jsonObject.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun validatesEveryStepBeforeReturningExecutableRoute() {
        val invalid = listOf(
            "- back: {}\n- unknown: {}",
            "- tap: {index: 4}",
            "- tap: {text: Open, within: {text: Project}}",
            "- tap: {text_exact: true}",
            "- delay: {milliseconds: 5001}",
            "- delay: {milliseconds: -1}",
            "- delay: {milliseconds: '100'}",
            "- delay: {milliseconds: 1.5}",
            "- input_text: {text: secret}",
            "- input_text: {resource_id: field}",
            "- scroll: {direction: diagonal}",
            "- wait: {condition: unknown, text: Ready}",
            "- wait: {condition: visible}",
            "- assert: {condition: count, text: Card, expected: '-1'}",
            "- assert: {condition: count, text: Card, expected: '100'}",
            "- wait: {condition: idle, timeout_ms: 249}",
            "- back: {count: 2}",
            "- launch_app: {package_name: com.other.app}",
            "- back: {}\n  delay: {milliseconds: 10}",
            "- tap: {text: Open, text: Close}"
        )
        invalid.forEach { steps ->
            assertFailsWith<IllegalArgumentException>(steps) { YamlRouteParser.parse(source(steps)) }
        }
    }

    @Test
    fun rejectsUnsafeAndUnsupportedYaml() {
        listOf(
            "- tap: &target {text: Open}\n- tap: *target",
            "- tap: {text: !!str Open}",
            "- tap: {text: 2026-10-29}",
            "- back: {}\n---\n- back: {}",
            "- tap: {text: [a, b]}",
            "- back: null"
        ).forEach { steps ->
            assertFailsWith<IllegalArgumentException>(steps) { YamlRouteParser.parse(source(steps)) }
        }
        assertFailsWith<IllegalArgumentException> { YamlRouteParser.parse("x".repeat(32769)) }
    }

    @Test
    fun enforcesRouteLimitsAndPlatform() {
        listOf(
            source("- back: {}", "timeout_ms: 60001"),
            source("- back: {}", "unknown: true"),
            source("- back: {}").replace("platform: android", "platform: ios"),
            source("- back: {}").replace("schema_version: 1", "schema_version: 2"),
            source("- back: {}").replace("app_id: com.example.projects", "app_id: invalid"),
            source((1..51).joinToString("\n") { "- back: {}" }),
            source("[]")
        ).forEach { yaml ->
            assertFailsWith<IllegalArgumentException>(yaml) { YamlRouteParser.parse(yaml) }
        }
        assertEquals(50, YamlRouteParser.parse(source((1..50).joinToString("\n") { "- back: {}" })).steps.size)
    }

    @Test
    fun parametersAreTypedDataNotExpressions() {
        val yaml = source("""- delay: {milliseconds: "${'$'}{pause}"}""",
            "parameters: {pause: {type: integer}}")
        assertFailsWith<IllegalArgumentException> { YamlRouteParser.parse(yaml) }
        assertFailsWith<IllegalArgumentException> {
            YamlRouteParser.parse(yaml, buildJsonObject { put("pause", "100") })
        }
        assertFailsWith<IllegalArgumentException> {
            YamlRouteParser.parse(yaml, buildJsonObject { put("pause", 100); put("extra", 1) })
        }
        assertFailsWith<IllegalArgumentException> {
            YamlRouteParser.parse(source("""- tap: {text: "prefix ${'$'}{value}"}""",
                "parameters: {value: {type: string, default: Open}}"))
        }
        assertFailsWith<IllegalArgumentException> {
            YamlRouteParser.parse(source("""- tap: {text: "${'$'}{missing}"}"""))
        }
        val injected = "\n- back: {}"
        val route = YamlRouteParser.parse(source("""- tap: {text: "${'$'}{label}"}""",
            "parameters: {label: {type: string}}"), buildJsonObject { put("label", injected) })
        assertEquals(1, route.steps.size)
        assertEquals(injected, route.steps.single().arguments.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun runnerExecutesSeriallyAndReportsRequestedDelay() = runTest {
        val route = YamlRouteParser.parse(source("- back: {}\n- delay: {milliseconds: 100}\n- back: {}"))
        val calls = mutableListOf<String>()
        val result = YamlRouteRunner().run(route) { calls += it.id; ToolResult("OK") }
        assertFalse(result.isError)
        assertEquals(listOf("step_1", "step_3"), calls)
        val report = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("completed", report.getValue("status").jsonPrimitive.content)
        assertEquals("android_device", report.getValue("execution_location").jsonPrimitive.content)
        val steps = report.getValue("steps").jsonArray
        assertEquals(100, steps[1].jsonObject.getValue("requested_delay_ms").jsonPrimitive.int)
        assertTrue(steps.all { "duration_ms" in it.jsonObject && "started_at_ms" in it.jsonObject })
    }

    @Test
    fun runnerMeasuresRealDelayDuration() = runBlocking {
        val route = YamlRouteParser.parse(source("- delay: {milliseconds: 30}"))
        val result = YamlRouteRunner().run(route) { error("Delay must execute locally.") }
        val step = Json.parseToJsonElement(result.text).jsonObject.getValue("steps").jsonArray.single().jsonObject
        assertTrue(step.getValue("duration_ms").jsonPrimitive.long >= 30)
        assertEquals(30, step.getValue("requested_delay_ms").jsonPrimitive.int)
    }

    @Test
    fun runnerStopsWithoutRetryAndPreservesFailureImage() = runTest {
        val route = YamlRouteParser.parse(source("- back: {}\n- back: {}\n- back: {}"))
        var calls = 0
        val result = YamlRouteRunner().run(route) {
            calls++
            ToolResult("Failed", isError = true, imageBase64 = "image", imageMimeType = "image/jpeg")
        }
        assertTrue(result.isError)
        assertEquals(1, calls)
        assertEquals("image", result.imageBase64)
        assertEquals(listOf("failed", "skipped", "skipped"), statuses(result))
    }

    @Test
    fun runnerEnforcesOverallDeadline() = runTest {
        val route = YamlRouteParser.parse(source("- back: {}\n- back: {}", "timeout_ms: 250"))
        var calls = 0
        val result = YamlRouteRunner().run(route) { calls++; delay(1000); ToolResult("OK") }
        assertTrue(result.isError)
        assertEquals(1, calls)
        assertEquals(listOf("timed_out", "skipped"), statuses(result))
    }

    @Test
    fun runnerDetectsDeadlineExceededBySynchronousAction() = runBlocking {
        val route = YamlRouteParser.parse(source("- back: {}\n- back: {}", "timeout_ms: 5"))
        var calls = 0
        val result = YamlRouteRunner().run(route) {
            calls++
            Thread.sleep(20)
            ToolResult("Dispatched")
        }
        assertTrue(result.isError)
        assertEquals(1, calls)
        assertEquals(listOf("timed_out", "skipped"), statuses(result))
    }

    @Test
    fun runnerPropagatesCancellationAndReportsExceptions() = runTest {
        val route = YamlRouteParser.parse(source("- back: {}\n- back: {}"))
        assertFailsWith<CancellationException> {
            YamlRouteRunner().run(route) { throw CancellationException("client disconnected") }
        }
        val result = YamlRouteRunner().run(route) { throw IllegalStateException("private data") }
        assertEquals(listOf("failed", "skipped"), statuses(result))
        assertFalse(result.text.contains("private data"))
    }

    private fun statuses(result: ToolResult): List<String> =
        Json.parseToJsonElement(result.text).jsonObject.getValue("steps").jsonArray.map {
            it.jsonObject.getValue("status").jsonPrimitive.content
        }
}
