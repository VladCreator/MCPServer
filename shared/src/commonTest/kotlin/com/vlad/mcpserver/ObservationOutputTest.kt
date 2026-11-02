package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.ObservationOutput
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ObservationOutputTest {
    private fun payload(count: Int, text: String = "Synthetic") = buildJsonObject {
        put("truncated", false); put("unstable", false)
        putJsonArray("nodes") {
            repeat(count) { index -> add(buildJsonObject {
                put("node_index", index); put("text", text); put("element_ref", "ref-$index")
            }) }
        }
    }

    private fun wire(data: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", Long.MAX_VALUE)
        putJsonObject("result") {
            put("isError", false)
            putJsonArray("content") { add(buildJsonObject {
                put("type", "text"); put("text", ObservationOutput.summary("observe_screen", data))
            }) }
            put("structuredContent", JsonObject(data + ("flow_id" to JsonPrimitive(Long.MAX_VALUE))))
        }
    }.toString().encodeToByteArray().size

    @Test
    fun compactEightyNodesFitWireBudgetWithCorrelation() {
        val data = ObservationOutput.bound(payload(80, "日本語".repeat(200)), "nodes", false)
        assertTrue(wire(data) <= 24 * 1024)
        assertTrue(data["byte_truncated"]!!.jsonPrimitive.boolean)
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(80, data["returned_count"]!!.jsonPrimitive.int + data["omitted_count"]!!.jsonPrimitive.int)
        assertEquals(data["returned_count"]!!.jsonPrimitive.int, data["nodes"]!!.jsonArray.size)
    }

    @Test
    fun zeroMatchesFitTwoKiBAndSummaryDoesNotRepeatJson() {
        val data = ObservationOutput.bound(buildJsonObject {
            put("count", 0); put("truncated", false); put("unstable", false)
            put("matches", JsonArray(emptyList()))
        }, "matches", false)
        assertTrue(wire(data) <= 2 * 1024)
        assertFalse(ObservationOutput.summary("ui_find", data).contains(data.toString()))
        assertFalse(data["byte_truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun fullDetailIsStillBoundedAndKeepsExistingTruncation() {
        val data = ObservationOutput.bound(JsonObject(payload(100, "\"😀\n".repeat(1000)) +
            ("truncated" to JsonPrimitive(true))), "nodes", true)
        assertTrue(wire(data) <= 64 * 1024)
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("full", data["detail"]!!.jsonPrimitive.content)
        assertEquals(data, Json.parseToJsonElement(data.toString()).jsonObject)
    }

    @Test
    fun oversizedMetadataFailsExplicitlyRatherThanReturningOversizedSuccess() {
        assertFailsWith<IllegalArgumentException> {
            ObservationOutput.bound(JsonObject(payload(0) +
                ("unexpected_metadata" to JsonPrimitive("x".repeat(70 * 1024)))), "nodes", true)
        }
    }
}
