@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.Foundation.dataWithBytes
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * iOS QA report writer. The report lives in Documents/qa/reports so it can be
 * retrieved from the app container or shared through the host.
 */
class IosQaSessionManager {

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
        val id = "qa-" + timestampFile()
        current = Session(
            id = id,
            testCase = testCase.take(200),
            platform = platform.ifBlank { "ios" }.take(32),
            startedAt = now(),
            device = device,
            notes = notes.take(2_000)
        )
        return ToolResult(
            "QA session $id started for '${testCase.take(200)}' ($platform). " +
                "Record steps with action=step and close with action=end."
        )
    }

    fun step(title: String, expected: String, actual: String, status: String, notes: String): ToolResult {
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
        return ToolResult("Step ${step.number} recorded [$normalized]: ${step.title}")
    }

    fun status(): ToolResult {
        val session = current ?: return ToolResult("No active QA session.")
        val pass = session.steps.count { it.status == "pass" }
        val fail = session.steps.count { it.status == "fail" }
        val blocked = session.steps.count { it.status == "blocked" }
        return ToolResult(
            "QA session: ${session.id}\nTest case: ${session.testCase}\nPlatform: ${session.platform}\n" +
                "Steps: ${session.steps.size} (pass=$pass, fail=$fail, blocked=$blocked)\n" +
                "Report: pending (call action=end)"
        )
    }

    fun end(result: String, notes: String): ToolResult {
        val session = current ?: return ToolResult(
            "No active QA session. Call qa_session with action=begin first.",
            isError = true
        )
        val finalResult = result.ifBlank { "UNKNOWN" }.take(64)
        val directory = reportsDirectory()
        val jsonPath = "$directory/${session.id}.json"
        val markdownPath = "$directory/${session.id}.md"

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
            check(writeText(jsonPath, json.toString())) { "write failed: $jsonPath" }
            check(writeText(markdownPath, markdown)) { "write failed: $markdownPath" }
            current = null
            ToolResult(
                "QA session ${session.id} closed: $finalResult. JSON: $jsonPath\nMarkdown: $markdownPath"
            )
        }.getOrElse { error ->
            ToolResult("Unable to write the QA report: ${error.message}", isError = true)
        }
    }

    private fun reportsDirectory(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val documents = paths.firstOrNull() as? String ?: "."
        val directory = "$documents/qa/reports"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        return directory
    }

    private fun writeText(path: String, text: String): Boolean {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) return false
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
        return data.writeToFile(path, atomically = true)
    }

    private fun now(): String {
        val formatter = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd'T'HH:mm:ssXXX" }
        return formatter.stringFromDate(NSDate())
    }

    private fun timestampFile(): String {
        val formatter = NSDateFormatter().apply { dateFormat = "yyyyMMdd-HHmmss" }
        return formatter.stringFromDate(NSDate())
    }
}
