package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.RunInterruption
import com.vlad.mcpserver.mcp.RunPrivacy
import kotlinx.serialization.json.*
import kotlin.test.*

class RunPrivacyTest {
    @Test fun freeTextAndParametersNeverEnterPersistentSteps() {
        val raw = Json.parseToJsonElement("""{"id":"step_1","tool":"input_text","status":"failed",
            "output":"password abc","arguments":{"text":"secret"},"result":{"reason":"text_mismatch",
            "expected":"secret","actual":["personal"],"elapsed_ms":1,"passed":false}}""").jsonObject
        val safe = RunPrivacy.step(raw)
        for (text in listOf("password", "secret", "personal", "actual", "expected", "arguments", "output"))
            assertFalse(text in safe.toString())
        assertEquals("text_mismatch", safe.getValue("result").jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(safe.getValue("content_redacted").jsonPrimitive.boolean)
    }

    @Test fun canonicalPlansIgnoreObjectOrderingButPreserveStepOrderAndValues() {
        val a = Json.parseToJsonElement("""{"a":1,"b":{"z":2,"c":[1,2]}}""")
        val b = Json.parseToJsonElement("""{"b":{"c":[1,2],"z":2},"a":1}""")
        assertEquals(RunPrivacy.canonical(a), RunPrivacy.canonical(b))
        assertNotEquals(RunPrivacy.canonical(a), RunPrivacy.canonical(
            Json.parseToJsonElement("""{"a":1,"b":{"z":2,"c":[2,1]}}""")))
    }

    @Test fun interruptedDispatchIsNeverReportedCompletedOrAutomaticallyRetried() {
        val run = Json.parseToJsonElement("""{"active_step":"step_2","steps":[
            {"id":"step_1","tool":"click_element","status":"completed"},
            {"id":"step_2","tool":"input_text","status":"dispatched"},
            {"id":"step_3","tool":"delay","status":"pending"}]}""").jsonObject
        val recovered = RunInterruption.apply(run, "interrupted", "process_restart")
        assertEquals(listOf("completed", "interrupted", "skipped"), recovered.getValue("steps").jsonArray.map {
            it.jsonObject.getValue("status").jsonPrimitive.content
        })
        assertTrue(recovered.getValue("action_outcome_unknown").jsonPrimitive.boolean)
        assertTrue(recovered.getValue("pending_cleanup").jsonPrimitive.boolean)
    }

    @Test fun queueCancellationHasNoUnknownActionButEarlierMutationNeedsReview() {
        val queued = Json.parseToJsonElement("""{"steps":[{"id":"step_1","tool":"delay","status":"pending"}]}""").jsonObject
        val safe = RunInterruption.apply(queued, "cancelled", "cancel_requested")
        assertFalse(safe.getValue("action_outcome_unknown").jsonPrimitive.boolean)
        assertFalse(safe.getValue("pending_cleanup").jsonPrimitive.boolean)
        val mutated = Json.parseToJsonElement("""{"steps":[{"id":"step_1","tool":"click_element","status":"completed"}]}""").jsonObject
        assertTrue(RunInterruption.apply(mutated, "cancelled", "cancel_requested")
            .getValue("pending_cleanup").jsonPrimitive.boolean)
    }

    @Test fun scenarioRestartPreservesPrimaryVerdictAndOwnedPendingRestoration() {
        val run = Json.parseToJsonElement("""{"schema_version":2,"active_step":"cleanup_state",
            "primary_status":"failed","cleanup_status":"running","pending_cleanup":true,
            "pending_state_restore":true,"steps":[
            {"id":"step_1","tool":"ui_assert","status":"failed","phase":"steps"},
            {"id":"cleanup_state","tool":"restore_state","status":"dispatched","phase":"cleanup"}]}""").jsonObject
        val interrupted = RunInterruption.apply(run, "interrupted", "process_restart")
        assertEquals(JsonPrimitive("failed"), interrupted["primary_status"])
        assertEquals(JsonPrimitive("interrupted"), interrupted["cleanup_status"])
        assertEquals(JsonPrimitive(true), interrupted["pending_state_restore"])
        assertEquals(JsonPrimitive(true), interrupted["pending_cleanup"])
    }
}
