package com.vlad.mcpserver.mcp

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory QA session plus JSON/Markdown reports in the app sandbox.
 * One session is active at a time; the report is written by end().
 */
class QaSessionManager(private val context: Context) {

    private data class Step(
        val number: Int,
        val title: String,
        val expected: String,
        val actual: String,
        val status: String,
        val notes: String,
        val at: String
    )

    private data class Session(
        val id: String,
        val testCase: String,
        val platform: String,
        val startedAt: String,
        val device: String,
        val notes: String,
        val steps: MutableList<Step> = mutableListOf()
    )

    private var current: Session? = null

    fun begin(testCase: String, platform: String, device: String, notes: String): ToolResult {
        current?.let {
            return ToolResult(
                "QA session ${it.id} is already active. Close it with action=end before starting another.",
                isError = true
            )
        }
        if (testCase.isBlank()) return ToolResult("Missing required argument: test_case", isError = true)
        val id = "qa-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        current = Session(
            id = id,
            testCase = testCase.take(200),
            platform = platform.ifBlank { "android" }.take(32),
            startedAt = now(),
            device = device,
            notes = notes.take(2_000)
        )
        return ToolResult(
            "QA session $id started for '${testCase.take(200)}' ($platform). " +
                "Record steps with action=step and close with action=end."
        )
    }

    fun step(
        title: String,
        expected: String,
        actual: String,
        status: String,
        notes: String
    ): ToolResult {
        val session = current ?: return ToolResult(
            "No active QA session. Call qa_session with action=begin first.",
            isError = true
        )
        if (title.isBlank()) return ToolResult("Missing required argument: title", isError = true)
        val normalized = status.lowercase().ifBlank { "info" }
        if (normalized !in setOf("pass", "fail", "blocked", "info")) {
            return ToolResult("status must be pass, fail, blocked or info.", isError = true)
        }
        val step = Step(
            number = session.steps.size + 1,
            title = title.take(300),
            expected = expected.take(2_000),
            actual = actual.take(2_000),
            status = normalized,
            notes = notes.take(2_000),
            at = now()
        )
        session.steps += step
        return ToolResult(
            "Step ${step.number} recorded [$normalized]: ${step.title}" +
                if (expected.isNotBlank()) " (expected: ${expected.take(200)})" else ""
        )
    }

    fun status(): ToolResult {
        val session = current ?: return ToolResult("No active QA session.")
        val pass = session.steps.count { it.status == "pass" }
        val fail = session.steps.count { it.status == "fail" }
        val blocked = session.steps.count { it.status == "blocked" }
        val text = buildString {
            appendLine("QA session: ${session.id}")
            appendLine("Test case: ${session.testCase}")
            appendLine("Platform: ${session.platform}")
            appendLine("Started: ${session.startedAt}")
            appendLine("Steps: ${session.steps.size} (pass=$pass, fail=$fail, blocked=$blocked)")
            if (session.steps.isNotEmpty()) {
                appendLine("Last: ${session.steps.last().number}. ${session.steps.last().title} " +
                    "[${session.steps.last().status}]")
            }
            append("Report: pending (call action=end)")
        }
        return ToolResult(text)
    }

    fun end(result: String, notes: String): ToolResult {
        val session = current ?: return ToolResult(
            "No active QA session. Call qa_session with action=begin first.",
            isError = true
        )
        val finalResult = result.ifBlank { "UNKNOWN" }.take(64)
        val directory = File(context.filesDir, "qa/reports").apply { mkdirs() }
        val jsonFile = File(directory, "${session.id}.json")
        val markdownFile = File(directory, "${session.id}.md")

        val json = buildJsonObject {
            put("session", session.id)
            put("test_case", session.testCase)
            put("platform", session.platform)
            put("started_at", session.startedAt)
            put("ended_at", now())
            put("result", finalResult)
            put("notes", notes.take(2_000))
            put("device", session.device)
            putJsonArray("steps") {
                session.steps.forEach { step ->
                    add(buildJsonObject {
                        put("number", step.number)
                        put("title", step.title)
                        put("expected", step.expected)
                        put("actual", step.actual)
                        put("status", step.status)
                        put("notes", step.notes)
                        put("at", step.at)
                    })
                }
            }
        }
        val markdown = buildString {
            appendLine("# QA report — ${session.testCase}")
            appendLine()
            appendLine("- Session: ${session.id}")
            appendLine("- Platform: ${session.platform}")
            appendLine("- Started: ${session.startedAt}")
            appendLine("- Ended: ${now()}")
            appendLine("- Result: $finalResult")
            if (notes.isNotBlank()) appendLine("- Notes: ${notes.take(2_000)}")
            appendLine()
            appendLine("## Device")
            appendLine("```")
            appendLine(session.device.trim())
            appendLine("```")
            appendLine()
            appendLine("## Steps")
            if (session.steps.isEmpty()) {
                appendLine("_No steps recorded._")
            } else {
                session.steps.forEach { step ->
                    appendLine("### ${step.number}. ${step.title} — ${step.status.uppercase()}")
                    appendLine("- At: ${step.at}")
                    if (step.expected.isNotBlank()) appendLine("- Expected: ${step.expected}")
                    if (step.actual.isNotBlank()) appendLine("- Actual: ${step.actual}")
                    if (step.notes.isNotBlank()) appendLine("- Notes: ${step.notes}")
                    appendLine()
                }
            }
        }

        return runCatching {
            jsonFile.writeText(json.toString())
            markdownFile.writeText(markdown)
            current = null
            ToolResult(
                "QA session ${session.id} closed: $finalResult. " +
                    "JSON: ${jsonFile.path}\nMarkdown: ${markdownFile.path}"
            )
        }.getOrElse { error ->
            ToolResult("Unable to write the QA report: ${error.message}", isError = true)
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
}
