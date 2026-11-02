package com.vlad.mcpserver.mcp

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.io.IOException

/**
 * In-memory QA session plus JSON/Markdown reports in the app sandbox.
 * One session per agent is active at a time; a cumulative trace is saved on each step.
 */
class QaSessionManager(private val context: Context, private val clientId: String) {

    internal data class Step(
        val number: Int,
        val title: String,
        val expected: String,
        val actual: String,
        val status: String,
        val notes: String,
        val at: String,
        val evidenceName: String,
        val screenshotName: String?,
        val screenshotRequested: Boolean,
        val screenshotError: String?
    )

    private data class Session(
        val id: String,
        val flowId: Long,
        val testCase: String,
        val platform: String,
        val startedAt: String,
        val device: String,
        val notes: String,
        val steps: MutableList<Step> = mutableListOf()
    )

    private var current: Session? = null

    @Synchronized fun activeFlowId(): Long? = current?.flowId

    @Synchronized fun begin(testCase: String, platform: String, device: String, notes: String): ToolResult {
        current?.let {
            return ToolResult(
                "QA session ${it.id} is already active. Close it with action=end before starting another.",
                isError = true
            )
        }
        if (testCase.isBlank()) return ToolResult("Missing required argument: test_case", isError = true)
        val id = "qa-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            "-" + UUID.randomUUID().toString().take(8)
        val flowId = QaFlowStore.get(context).ensure("qa", id, clientId)
        current = Session(
            id = id,
            flowId = flowId,
            testCase = testCase.take(200),
            platform = platform.ifBlank { "android" }.take(32),
            startedAt = now(),
            device = device,
            notes = notes.take(2_000)
        )
        return ToolResult(
            "QA session $id started for '${testCase.take(200)}' ($platform). " +
                "Record steps with action=step and close with action=end.",
            flowId = flowId,
            structuredContent = buildJsonObject { put("flow_id", flowId) }
        )
    }

    @Synchronized fun step(
        title: String,
        expected: String,
        actual: String,
        status: String,
        notes: String,
        screenshot: ByteArray? = null,
        screenshotError: String? = null,
        screenshotRequested: Boolean = screenshot != null || screenshotError != null
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
        val number = session.steps.size + 1
        val screenshotName = qaStepScreenshotName(number, session.id.substringAfterLast('-'))
        val evidenceDirectory = File(context.filesDir, "qa/evidence")
        if (!evidenceDirectory.isDirectory && !evidenceDirectory.mkdirs()) {
            return ToolResult("Unable to create QA step evidence folder.", isError = true,
                structuredContent = buildJsonObject { put("reason", "evidence_storage_failed") })
        }
        val step = Step(
            number = number,
            title = title.take(300),
            expected = expected.take(2_000),
            actual = actual.take(2_000),
            status = normalized,
            notes = notes.take(2_000),
            at = now(),
            evidenceName = "steps-${session.id}.txt",
            screenshotName = screenshot?.let { screenshotName },
            screenshotRequested = screenshotRequested,
            screenshotError = (screenshotError
                ?: if (screenshotRequested && screenshot == null) "Requested screenshot was not captured." else null)?.take(300)
        )
        val trace = File(evidenceDirectory, step.evidenceName)
        val photo = step.screenshotName?.let { File(evidenceDirectory, it) }
        try {
            val text = qaSessionEvidenceText(session.testCase, session.flowId, session.startedAt, session.steps + step)
            writeQaStepEvidence(trace, text, photo, screenshot)
        } catch (error: Exception) {
            return ToolResult("Unable to save QA step evidence: ${error.message}", isError = true,
                structuredContent = buildJsonObject { put("reason", "evidence_storage_failed") })
        }
        session.steps += step
        val paths = listOfNotNull(trace.absolutePath, photo?.takeIf { it.isFile }?.absolutePath)
        val captureFailed = screenshotRequested && screenshot == null
        val captureError = screenshotError ?: "Requested screenshot was not captured."
        return ToolResult(
            "Step ${step.number} recorded [$normalized]: ${step.title}" +
                (if (expected.isNotBlank()) " (expected: ${expected.take(200)})" else "") +
                (if (captureFailed) ". Screenshot evidence failed: ${captureError.take(300)}" else
                    ". Evidence saved: ${listOfNotNull(step.evidenceName, step.screenshotName).joinToString(", ")}"),
            isError = captureFailed,
            flowId = session.flowId,
            evidencePaths = paths,
            structuredContent = buildJsonObject {
                put("step", step.number); put("status", normalized)
                put("evidence_file", step.evidenceName)
                step.screenshotName?.let { put("screenshot_file", it) }
                if (captureFailed) put("screenshot_error", captureError.take(300))
                put("screenshot_requested", screenshotRequested)
                putJsonArray("evidence_files") { paths.forEach { add(it.substringAfterLast('/')) } }
                put("flow_id", session.flowId)
            }
        )
    }

    @Synchronized fun status(): ToolResult {
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

    @Synchronized fun end(result: String, notes: String): ToolResult {
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
            put("flow_id", session.flowId)
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
                        put("evidence_file", step.evidenceName)
                        step.screenshotName?.let { put("screenshot_file", it) }
                        put("screenshot_requested", step.screenshotRequested)
                        step.screenshotError?.let { put("screenshot_error", it) }
                    })
                }
            }
        }
        val markdown = buildString {
            appendLine("# QA report — ${session.testCase}")
            appendLine()
            appendLine("- Session: ${session.id}")
            appendLine("- Flow: #${session.flowId}")
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
                    appendLine("- Screenshot: ${when {
                        step.screenshotName != null -> step.screenshotName
                        step.screenshotError != null -> "not captured (${step.screenshotError})"
                        else -> "not requested"
                    }}")
                    appendLine("- Evidence: ${listOfNotNull(step.evidenceName, step.screenshotName).joinToString(", ")}")
                    appendLine()
                }
            }
        }

        return runCatching {
            jsonFile.writeText(json.toString())
            markdownFile.writeText(markdown)
            QaFlowStore.get(context).bind(jsonFile.path, session.flowId)
            QaFlowStore.get(context).bind(markdownFile.path, session.flowId)
            current = null
            ToolResult(
                "QA session ${session.id} closed: $finalResult. " +
                    "View or share these reports in Tester's QA Evidence dashboard section.\n" +
                    "JSON: ${jsonFile.path}\nMarkdown: ${markdownFile.path}",
                flowId = session.flowId,
                structuredContent = buildJsonObject { put("flow_id", session.flowId) }
            )
        }.getOrElse { error ->
            ToolResult("Unable to write the QA report: ${error.message}", isError = true)
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
}

internal fun qaStepScreenshotName(number: Int, sessionKey: String): String {
    require(number > 0 && sessionKey.matches(Regex("[a-fA-F0-9]{8}"))) { "Invalid QA step evidence identity." }
    val prefix = "s${number.toString().padStart(2, '0')}-${sessionKey.lowercase()}"
    return "$prefix.jpg"
}

internal fun qaSessionEvidenceText(
    testCase: String, flowId: Long, startedAt: String, steps: List<QaSessionManager.Step>
): String = buildString {
    appendLine("QA steps: $testCase")
    appendLine("Flow: #$flowId")
    appendLine("Started: $startedAt")
    appendLine()
    steps.forEach { step ->
        appendLine("${step.number}. ${step.title}")
        appendLine("   Recorded: ${step.at}")
        appendLine("   Status: ${step.status.uppercase()}")
        if (step.expected.isNotBlank()) appendLine("   Expected: ${step.expected}")
        if (step.actual.isNotBlank()) appendLine("   Actual: ${step.actual}")
        if (step.notes.isNotBlank()) appendLine("   Notes: ${step.notes}")
        appendLine("   Screenshot: ${when {
            step.screenshotName != null -> step.screenshotName
            step.screenshotError != null -> "not captured (${step.screenshotError})"
            !step.screenshotRequested -> "not requested"
            else -> "requested, not captured"
        }}")
        appendLine()
    }
}

internal fun writeQaStepEvidence(trace: File, text: String, photo: File?, screenshot: ByteArray?) {
    var photoSaved = false
    try {
        if (screenshot != null) {
            val target = checkNotNull(photo)
            check(!target.exists()) { "Step screenshot filename already exists." }
            writeQaEvidenceFile(target) { it.writeBytes(screenshot) }
            photoSaved = true
        }
        writeQaEvidenceFile(trace) { it.writeText(text) }
    } catch (error: Exception) {
        if (photoSaved && photo != null && !photo.delete()) {
            error.addSuppressed(IOException("Unable to remove incomplete step screenshot."))
        }
        throw error
    }
}

internal fun writeQaEvidenceFile(file: File, write: (File) -> Unit) {
    val directory = checkNotNull(file.parentFile)
    check(directory.isDirectory || directory.mkdirs()) { "Unable to create QA evidence folder." }
    val temporary = File(directory, "${file.name}.tmp")
    try {
        write(temporary)
        if (!temporary.renameTo(file)) throw IOException("Unable to finalize QA evidence: ${file.name}")
    } catch (error: Exception) {
        if (temporary.exists() && !temporary.delete()) {
            error.addSuppressed(IOException("Unable to remove incomplete QA evidence."))
        }
        throw error
    }
}
