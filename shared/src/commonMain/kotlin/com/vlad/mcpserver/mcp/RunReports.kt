package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.*

internal object RunReports {
    fun export(run: JsonObject, format: String): Pair<String, String> = when (format) {
        "json" -> "application/json" to run.toString()
        "markdown" -> "text/markdown" to buildString {
            appendLine("# Tester run ${run.value("run_id")}")
            if (run["flow_id"] != null) appendLine("\nFlow: #${run.value("flow_id")}")
            appendLine("\nStatus: ${run.value("status")}; primary: ${run.value("primary_status")}; cleanup: ${run.value("cleanup_status")}")
            appendLine("\nFree text and step arguments are redacted.\n")
            appendLine("| Step | Phase | Tool | Status | Offset (ms) | Duration (ms) |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            run.getValue("steps").jsonArray.forEach {
                val step = it.jsonObject
                appendLine("| ${step.value("id")} | ${step.value("phase")} | ${step.value("tool")} | ${step.value("status")} | ${step.value("offset_ms")} | ${step.value("duration_ms")} |")
            }
        }
        "junit" -> "application/xml" to junit(run)
        else -> throw IllegalArgumentException("format must be json, markdown or junit.")
    }

    private fun junit(run: JsonObject): String {
        val steps = run.getValue("steps").jsonArray.map { it.jsonObject }
        val failed = steps.count { it.value("status") !in setOf("completed", "skipped", "pending") }
        val skipped = steps.count { it.value("status") in setOf("skipped", "pending") }
        val unsuccessful = run.value("status") != "completed" || run["pending_cleanup"] == JsonPrimitive(true)
        val verdictFailed = unsuccessful
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<testsuite name="Tester" id="${xml(run.value("run_id"))}" tests="${steps.size + 1}" failures="${failed + if (verdictFailed) 1 else 0}" skipped="$skipped" time="${seconds(run)}">""")
            for (step in steps) {
                append("""  <testcase classname="${xml(run.value("run_id"))}" name="${xml(step.value("id"))}" time="${seconds(step)}">""")
                when (step.value("status")) {
                    "completed" -> Unit
                    "skipped", "pending" -> append("<skipped/>")
                    else -> append("""<failure message="${xml(step.value("status"))}">${xml(step.value("phase"))}:${xml(step.value("tool"))}</failure>""")
                }
                appendLine("</testcase>")
            }
            append("""  <testcase classname="${xml(run.value("run_id"))}" name="run_verdict" time="0">""")
            if (verdictFailed) append("""<failure message="${xml(run.value("status"))}">primary=${xml(run.value("primary_status"))}; cleanup=${xml(run.value("cleanup_status"))}; pending=${xml(run.value("pending_cleanup"))}</failure>""")
            appendLine("</testcase>\n</testsuite>")
        }
    }

    private fun seconds(value: JsonObject): String = ((value["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0L)
        .coerceAtLeast(0).let { "${it / 1000}.${(it % 1000).toString().padStart(3, '0')}" }
    private fun JsonObject.value(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
