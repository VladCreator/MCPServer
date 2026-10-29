package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class PredicateOutcome(
    val passed: Boolean,
    val reason: String,
    val actual: List<String?>,
    val truncated: Boolean = false
) {
    fun structured(condition: String, expected: String?, elapsedMs: Long): JsonObject = buildJsonObject {
        put("condition", condition)
        put("passed", passed)
        put("reason", reason)
        put("expected", expected?.let(::JsonPrimitive) ?: JsonNull)
        put("actual", JsonArray(actual.map { it?.let(::JsonPrimitive) ?: JsonNull }))
        put("truncated", truncated)
        put("elapsed_ms", elapsedMs)
        put("retryable", !passed && reason !in setOf("ambiguous_match", "truncated_count", "invalid_condition"))
    }
}

internal object UiPredicate {
    fun evaluate(
        condition: String,
        texts: List<String?>,
        expected: String?,
        hasWindow: Boolean,
        foreground: String?,
        targetPackage: String?,
        truncated: Boolean = false,
        contains: Boolean = false,
        ignoreCase: Boolean = false
    ): PredicateOutcome {
        fun result(passed: Boolean, reason: String) = PredicateOutcome(passed, reason, texts, truncated)
        if (!hasWindow) return result(false, "no_window")
        if (targetPackage != null && targetPackage != foreground) return result(false, "wrong_package")
        return when (condition) {
            "visible" -> result(texts.isNotEmpty(), if (texts.isEmpty()) "no_match" else "matched")
            "absent" -> result(texts.isEmpty(), if (texts.isEmpty()) "matched" else "element_present")
            "count" -> if (truncated) result(false, "truncated_count")
                else result(expected?.toIntOrNull() == texts.size,
                    if (expected?.toIntOrNull() == texts.size) "matched" else "count_mismatch")
            "text" -> when {
                texts.isEmpty() -> result(false, "no_match")
                texts.size > 1 || truncated -> result(false, "ambiguous_match")
                expected == null -> result(true, "matched")
                texts.single()?.let {
                    if (contains) it.contains(expected, ignoreCase) else it.equals(expected, ignoreCase)
                } == true -> result(true, "matched")
                else -> result(false, "text_mismatch")
            }
            else -> result(false, "invalid_condition")
        }
    }
}
