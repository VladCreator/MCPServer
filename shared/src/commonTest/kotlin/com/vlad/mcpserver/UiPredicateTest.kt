package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.UiPredicate
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiPredicateTest {
    private fun evaluate(
        condition: String = "text", texts: List<String?> = listOf("Tester"),
        expected: String? = "Tester", window: Boolean = true, foreground: String? = "app",
        truncated: Boolean = false, contains: Boolean = false, ignoreCase: Boolean = false
    ) = UiPredicate.evaluate(condition, texts, expected, window, foreground, "app", truncated, contains, ignoreCase)

    @Test
    fun comparesActualTextInsteadOfSelectorExistence() {
        assertTrue(evaluate().passed)
        assertFalse(evaluate(expected = "wrong").passed)
        assertEquals("text_mismatch", evaluate(expected = "wrong").reason)
        assertFalse(evaluate(expected = "tester").passed)
        assertTrue(evaluate(expected = "tester", ignoreCase = true).passed)
        assertFalse(evaluate(expected = "Test").passed)
        assertTrue(evaluate(expected = "Test", contains = true).passed)
        assertTrue(evaluate(texts = listOf(""), expected = "").passed)
        assertFalse(evaluate(texts = listOf(null), expected = "").passed)
        assertTrue(evaluate(expected = null).passed)
    }

    @Test
    fun refusesAmbiguousAndMissingText() {
        assertEquals("no_match", evaluate(texts = emptyList()).reason)
        assertEquals("ambiguous_match", evaluate(texts = listOf("Tester", "Tester")).reason)
        assertFalse(evaluate(texts = listOf("Tester", "Wrong")).passed)
    }

    @Test
    fun absentAndZeroCountNeedTheCorrectWindow() {
        for (condition in listOf("absent", "count", "visible", "text")) {
            assertEquals("no_window", evaluate(condition, emptyList(), "0", window = false).reason)
            assertEquals("wrong_package", evaluate(condition, emptyList(), "0", foreground = "other").reason)
            assertFalse(evaluate(condition, emptyList(), "0", window = false).passed)
        }
        assertTrue(evaluate("absent", emptyList()).passed)
        assertTrue(evaluate("count", emptyList(), "0").passed)
        assertFalse(evaluate("absent", listOf("x")).passed)
    }

    @Test
    fun neverClaimsExactCountFromTruncatedMatches() {
        assertEquals("truncated_count", evaluate("count", List(100) { "x" }, "100", truncated = true).reason)
        assertFalse(evaluate("count", List(100) { "x" }, "100", truncated = true).passed)
        assertTrue(evaluate("count", listOf("a", "b"), "2").passed)
        assertFalse(evaluate("count", listOf("a", "b"), "1").passed)
        assertTrue(evaluate("visible", List(100) { "x" }, truncated = true).passed)
    }

    @Test
    fun structuredOutcomePreservesActualExpectedAndReason() {
        val outcome = evaluate(expected = "Wrong")
        val result = outcome.structured("text", "Wrong", 500)
        assertEquals("text_mismatch", result.getValue("reason").jsonPrimitive.content)
        assertEquals("Wrong", result.getValue("expected").jsonPrimitive.content)
        assertEquals("500", result.getValue("elapsed_ms").jsonPrimitive.content)
        assertEquals("false", result.getValue("passed").jsonPrimitive.content)
    }
}
