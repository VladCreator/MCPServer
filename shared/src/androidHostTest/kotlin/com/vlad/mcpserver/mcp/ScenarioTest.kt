package com.vlad.mcpserver.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class ScenarioTest {
    private fun scenario(extra: String = "") = """
        schema_version: 2
        id: fixture.test
        version: 1.0.0
        platform: android
        app_id: com.vlad.mcpserver.fixture
        setup:
          - device_state: {keep_awake: true}
          - launch_app: {}
        steps:
          - tap: {resource_id: open}
        postconditions:
          - assert: {condition: text, resource_id: status, expected: Ready}
        cleanup:
          - back: {}
        $extra
    """.trimIndent()

    @Test fun yamlKeepsItsOwnSourceSizeLimitWithoutDoubleCountingJsonEscaping() {
        val source = "schema_version: 1\nid: legacy.escaped\nplatform: android\napp_id: com.example.app\nsteps:\n" +
            (1..4).joinToString("\n") { "  - tap: {text: '" + "\\".repeat(4500) + "'}" }
        assertTrue(source.length < 32768)
        assertEquals(4, YamlRouteParser.parse(source).steps.size)
    }

    @Test fun yamlAndStructuredInputUseIdenticalModel() {
        val yaml = YamlRouteParser.parse(scenario())
        val json = buildJsonObject {
            put("schema_version", 2); put("id", "fixture.test"); put("version", "1.0.0")
            put("platform", "android"); put("app_id", "com.vlad.mcpserver.fixture")
            put("setup", Json.parseToJsonElement("""[{"device_state":{"keep_awake":true}},{"launch_app":{}}]"""))
            put("steps", Json.parseToJsonElement("""[{"tap":{"resource_id":"open"}}]"""))
            put("postconditions", Json.parseToJsonElement("""[{"assert":{"condition":"text","resource_id":"status","expected":"Ready"}}]"""))
            put("cleanup", Json.parseToJsonElement("""[{"back":{}}]"""))
        }
        assertEquals(yaml, YamlRouteParser.parse(json))
        assertEquals(listOf("setup_1", "setup_2", "step_1", "postconditions_1", "cleanup_1", "cleanup_state"),
            yaml.allSteps.map { it.id })
    }

    @Test fun validatesEntireScenarioAndNeverAllowsArbitraryCleanupTools() {
        for (invalid in listOf(
            scenario().replace("version: 1.0.0", "version: latest"),
            scenario().replace("- back: {}", "- shell: {command: unsafe}"),
            scenario().replace("- back: {}", "- device_state: {volume: 0}"),
            scenario().replace("- device_state: {keep_awake: true}", "- device_state: {state_key: secret}"),
            scenario().replace("- device_state: {keep_awake: true}", "- device_state: {dark_mode: on}"),
            scenario().replace("- device_state: {keep_awake: true}", "- device_state: {volume: 101}"),
            scenario().replace("- device_state: {keep_awake: true}", "- open_link: {uri: 'file://local/x'}"),
            scenario().replace("- assert: {condition: text, resource_id: status, expected: Ready}", "- tap: {text: Ready}"),
            scenario("cleanup_timeout_ms: 5000"),
            scenario("preconditions: [{launch_app: {}}]")
        )) assertFailsWith<IllegalArgumentException>(invalid) { YamlRouteParser.parse(invalid) }
    }

    @Test fun uiPreconditionsCheckPreparedAppBeforeBusinessActions() = runTest {
        val route = YamlRouteParser.parse(scenario(
            "preconditions: [{assert: {condition: visible, text: Ready}}]"))
        val calls = mutableListOf<String>()
        val result = YamlRouteRunner().run(route) {
            calls += it.id
            ToolResult("not ready", isError = it.phase == "preconditions")
        }
        assertTrue(result.isError)
        assertEquals(listOf("setup_1", "setup_2", "preconditions_1", "cleanup_1", "cleanup_state"), calls)
    }

    @Test fun catalogIsExactVersionDigestPinnedAndCompilesAllThreeDefinitions() {
        val catalog = ScenarioCatalog { File("../androidApp/src/main/assets/$it").readText() }
        val entries = catalog.list().getValue("routes").jsonArray
        assertEquals(3, entries.size)
        for (entry in entries) {
            val descriptor = entry.jsonObject
            val definition = catalog.get(descriptor)
            val route = catalog.resolve(descriptor)
            assertEquals(2, route.schemaVersion)
            assertEquals(descriptor.getValue("digest").jsonPrimitive.content, route.catalogDigest)
            assertTrue(route.changesState)
            assertEquals("restore_state", route.cleanup.last().tool)
            assertTrue(definition.getValue("yaml").jsonPrimitive.content.contains("schema_version: 2"))
            assertFailsWith<IllegalArgumentException> { catalog.resolve(JsonObject(descriptor - "digest")) }
            assertFailsWith<IllegalArgumentException> {
                catalog.resolve(JsonObject(descriptor + ("version" to JsonPrimitive("latest"))))
            }
            assertFailsWith<IllegalArgumentException> {
                catalog.resolve(JsonObject(descriptor + ("digest" to JsonPrimitive("0".repeat(64)))))
            }
        }
    }

    @Test fun setupFailureSkipsBusinessStepsButStillCleansAndRestores() = runTest {
        val calls = mutableListOf<String>()
        val output = YamlRouteRunner().run(YamlRouteParser.parse(scenario())) { step ->
            calls += step.id
            ToolResult("private", isError = step.phase == "setup")
        }.structuredContent!!
        assertEquals(listOf("setup_1", "cleanup_1", "cleanup_state"), calls)
        assertEquals("failed", output.string("primary_status"))
        assertEquals("completed", output.string("cleanup_status"))
        assertEquals(JsonPrimitive(false), output["pending_cleanup"])
    }

    @Test fun cleanupFailureDoesNotErasePrimaryVerdictAndContinuesRestoration() = runTest {
        val calls = mutableListOf<String>()
        val result = YamlRouteRunner().run(YamlRouteParser.parse(scenario())) {
            calls += it.id
            ToolResult("failure", isError = it.id in setOf("step_1", "cleanup_1"))
        }
        val data = result.structuredContent!!
        assertTrue(result.isError)
        assertEquals("failed", data.string("primary_status"))
        assertEquals("failed", data.string("cleanup_status"))
        assertEquals(JsonPrimitive(false), data["pending_state_restore"])
        assertEquals("cleanup_state", calls.last())
        val primary = data.getValue("steps").jsonArray.first { it.jsonObject.string("id") == "step_1" }
        assertEquals("failed", primary.jsonObject.string("status"))
    }

    @Test fun cleanupFailureTurnsSuccessfulTestIntoFailure() = runTest {
        val result = YamlRouteRunner().run(YamlRouteParser.parse(scenario())) {
            ToolResult("result", isError = it.tool == "restore_state")
        }
        val data = result.structuredContent!!
        assertTrue(result.isError)
        assertEquals("completed", data.string("primary_status"))
        assertEquals("failed", data.string("status"))
        assertEquals(JsonPrimitive(true), data["pending_state_restore"])
    }

    @Test fun deadlineAndCleanupHaveIndependentBudgets() = runTest {
        val route = YamlRouteParser.parse(scenario("timeout_ms: 5"))
        val calls = mutableListOf<String>()
        val result = YamlRouteRunner().run(route) {
            calls += it.id
            if (it.phase != "cleanup") delay(1000)
            ToolResult("ok")
        }.structuredContent!!
        assertEquals("timed_out", result.string("primary_status"))
        assertEquals("completed", result.string("cleanup_status"))
        assertEquals(listOf("setup_1", "cleanup_1", "cleanup_state"), calls)
    }

    @Test fun exhaustedUserCleanupBudgetStillAttemptsAutomaticRestoration() = runTest {
        val calls = mutableListOf<String>()
        val result = YamlRouteRunner().run(YamlRouteParser.parse(scenario("cleanup_timeout_ms: 6000"))) {
            calls += it.id
            if (it.id == "cleanup_1") delay(2000)
            ToolResult("ok")
        }.structuredContent!!
        assertEquals("failed", result.string("cleanup_status"))
        assertEquals("cleanup_state", calls.last())
        assertEquals(JsonPrimitive(false), result["pending_state_restore"])
    }

    @Test fun externalCancellationRunsCleanupWithoutResumingActions() = runTest {
        val calls = mutableListOf<String>()
        val dispatched = CompletableDeferred<Unit>()
        val task = launch {
            YamlRouteRunner().run(YamlRouteParser.parse(scenario())) {
                calls += it.id
                if (it.id == "step_1") { dispatched.complete(Unit); awaitCancellation() }
                ToolResult("ok")
            }
        }
        dispatched.await()
        task.cancelAndJoin()
        assertEquals(listOf("setup_1", "setup_2", "step_1", "cleanup_1", "cleanup_state"), calls)
    }

    @Test fun persistenceFailurePreventsActionsButDoesNotPreventRestoration() = runTest {
        val calls = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            YamlRouteRunner().run(YamlRouteParser.parse(scenario()),
                onStep = { if (it.string("id") == "step_1") error("Storage unavailable") }) {
                calls += it.id
                ToolResult("ok")
            }
        }
        assertFalse("step_1" in calls)
        assertEquals("cleanup_state", calls.last())
    }

    @Test fun consentIsSessionPlanPrivacyAndAttemptScoped() {
        var clock = 0L
        val policies = ScenarioPolicies { clock }
        val route = YamlRouteParser.parse(scenario()).copy(catalogDigest = "reviewed")
        val identity = policies.identity(route, true)
        val policy = policies.grant("owner", identity, 2, 1000).getValue("policy_id").jsonPrimitive.content
        assertFalse(policies.consume("other", policy, identity))
        assertFalse(policies.consume("owner", policy, policies.identity(route, false)))
        assertFalse(policies.consume("owner", policy, policies.identity(route.copy(timeoutMs = 100), true)))
        assertTrue(policies.consume("owner", policy, identity))
        assertTrue(policies.consume("owner", policy, identity))
        assertFalse(policies.consume("owner", policy, identity))
        val expiring = policies.grant("owner", identity, 2, 1000).getValue("policy_id").jsonPrimitive.content
        clock = 1000
        assertFalse(policies.consume("owner", expiring, identity))
        policies.revoke("owner", expiring)
        assertFailsWith<IllegalStateException> { policies.status("owner", expiring) }
        policies.clear()
        assertFalse(policies.consume("owner", policy, identity))
    }

    @Test fun junitUsesNativeStepIdsAndFailsOnCleanupOrInterruptedRun() {
        fun run(status: String) = buildJsonObject {
            put("run_id", "known-run"); put("status", status); put("primary_status", "completed")
            put("cleanup_status", "failed"); put("pending_cleanup", true); put("duration_ms", 1234)
            put("steps", JsonArray(listOf(buildJsonObject {
                put("id", "cleanup_state"); put("phase", "cleanup"); put("tool", "restore_state")
                put("status", "failed"); put("duration_ms", 123)
            })))
        }
        for (status in listOf("completed", "interrupted", "cancelled", "running", "failed")) {
            val xml = RunReports.export(run(status), "junit").second
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            assertEquals("known-run", doc.documentElement.getAttribute("id"))
            assertEquals("2", doc.documentElement.getAttribute("failures"))
            assertEquals("cleanup_state", doc.getElementsByTagName("testcase").item(0).attributes.getNamedItem("name").nodeValue)
            assertTrue(xml.contains("1.234"))
        }
    }
}
