package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.StateRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class StateRecoveryTest {
    private class Fixture {
        var stored: JsonObject? = null
        val device = mutableMapOf<String, JsonElement>("volume" to JsonPrimitive(4), "rotation" to JsonPrimitive(0))
        var blocked: String? = null
        var failWrite: String? = null
        var failSave = false
        val events = mutableListOf<String>()
        fun controller() = StateRecovery(
            load = { stored },
            save = { check(!failSave) { "Disk full" }; events += "persist"; stored = it },
            preflight = { check(it != blocked) { "Permission denied" } },
            read = { device.getValue(it) },
            writeAndVerify = { field, value ->
                events += "write:$field"
                if (field == failWrite) error("Policy rejected write")
                device[field] = value
            },
            logFailure = { _, _ -> }
        )
    }

    @Test fun snapshotsOnlyTouchedFieldsBeforeWritingAndRestoresWithoutOtherPermissions() = runTest {
        val f = Fixture()
        val c = f.controller()
        assertFalse(c.set("owner", buildJsonObject { put("volume", 2) }).isError)
        assertEquals(listOf("persist", "write:volume"), f.events)
        assertEquals(setOf("volume"), f.stored!!.getValue("fields").jsonObject.keys)
        f.blocked = "rotation"
        assertFalse(c.restore("owner").isError)
        assertEquals(JsonPrimitive(4), f.device["volume"])
        assertNull(f.stored)
    }

    @Test fun rejectsForeignOwnerWithoutWritesOrSnapshotReplacement() = runTest {
        val f = Fixture()
        val c = f.controller()
        c.set("first", buildJsonObject { put("volume", 2) })
        val original = f.stored
        val count = f.events.size
        assertTrue(c.set("foreign", buildJsonObject { put("rotation", 1) }).isError)
        assertTrue(c.restore("foreign").isError)
        assertEquals(count, f.events.size)
        assertEquals(original, f.stored)
    }

    @Test fun repeatedSetPreservesOriginalAndAddsOnlyNewFields() = runTest {
        val f = Fixture()
        val c = f.controller()
        c.set("owner", buildJsonObject { put("volume", 2) })
        c.set("owner", buildJsonObject { put("volume", 1); put("rotation", 1) })
        assertFalse(c.restore("owner").isError)
        assertEquals(JsonPrimitive(4), f.device["volume"])
        assertEquals(JsonPrimitive(0), f.device["rotation"])
    }

    @Test fun preflightRejectsEntireRequestBeforeAnySideEffect() = runTest {
        val f = Fixture()
        f.blocked = "rotation"
        assertFailsWith<IllegalStateException> {
            f.controller().set("owner", buildJsonObject { put("volume", 2); put("rotation", 1) })
        }
        assertTrue(f.events.isEmpty())
        assertNull(f.stored)
        assertEquals(JsonPrimitive(4), f.device["volume"])
    }

    @Test fun diskFailurePreventsDispatch() = runTest {
        val f = Fixture()
        f.failSave = true
        assertFailsWith<IllegalStateException> { f.controller().set("owner", buildJsonObject { put("volume", 2) }) }
        assertTrue(f.events.isEmpty())
    }

    @Test fun partialRestoreRemovesSuccessesButPersistsFailedFieldsForRetry() = runTest {
        val f = Fixture()
        val c = f.controller()
        c.set("owner", buildJsonObject { put("volume", 2); put("rotation", 1) })
        f.blocked = "rotation"
        assertTrue(c.restore("owner").isError)
        assertEquals(setOf("rotation"), f.stored!!.getValue("fields").jsonObject.keys)
        assertEquals(JsonPrimitive(4), f.device["volume"])
        f.blocked = null
        assertFalse(f.controller().restore("owner").isError)
        assertNull(f.stored)
    }

    @Test fun newControllerRecoversDurableOriginalWithoutReplayingSet() = runTest {
        val f = Fixture()
        f.controller().set("owner", buildJsonObject { put("volume", 2) })
        val count = f.events.size
        val restarted = f.controller()
        assertTrue(restarted.pending())
        assertEquals(count, f.events.size)
        assertFalse(restarted.restore("owner").isError)
        assertEquals(JsonPrimitive(4), f.device["volume"])
    }

    @Test fun originallyUnsetSettingRemainsUnsetAfterRestore() = runTest {
        val f = Fixture()
        f.device["rotation"] = JsonNull
        val c = f.controller()
        c.set("owner", buildJsonObject { put("rotation", 1) })
        assertEquals(JsonNull, f.stored!!.getValue("fields").jsonObject["rotation"])
        assertFalse(c.restore("owner").isError)
        assertEquals(JsonNull, f.device["rotation"])
    }

    @Test fun failedSetPreservesPrimaryErrorAndRetainsOnlyFailedCleanup() = runTest {
        val f = Fixture()
        f.failWrite = "rotation"
        val result = f.controller().set("owner", buildJsonObject { put("volume", 2); put("rotation", 1) })
        assertTrue(result.isError)
        assertEquals("set_failed_cleanup_pending", result.structuredContent!!["reason"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(4), f.device["volume"])
        assertEquals(setOf("rotation"), f.stored!!.getValue("fields").jsonObject.keys)
    }

    @Test fun cancellationRestoresInNonCancellableCleanup() = runTest {
        var stored: JsonObject? = null
        var device = JsonPrimitive(4)
        val dispatched = CompletableDeferred<Unit>()
        val c = StateRecovery({ stored }, { stored = it }, {}, { device }, { _, value ->
            device = value.jsonPrimitive
            if (value == JsonPrimitive(2)) { dispatched.complete(Unit); awaitCancellation() }
        }, { _, _ -> })
        val job = launch { c.set("owner", buildJsonObject { put("volume", 2) }) }
        dispatched.await()
        job.cancelAndJoin()
        assertEquals(JsonPrimitive(4), device)
        assertNull(stored)
    }
}
