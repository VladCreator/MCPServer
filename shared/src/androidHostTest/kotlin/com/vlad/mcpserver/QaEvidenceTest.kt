package com.vlad.mcpserver

import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.vlad.mcpserver.mcp.QaSessionManager
import com.vlad.mcpserver.mcp.qaSessionEvidenceText
import com.vlad.mcpserver.mcp.qaStepScreenshotName
import com.vlad.mcpserver.mcp.writeQaStepEvidence
import com.vlad.mcpserver.mcp.writeQaEvidenceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaEvidenceTest {
    @Test
    fun prettyJsonIndentsNestedValuesWithoutChangingContent() {
        val source = """{"name":"QA","steps":[{"passed":true}],"count":null}"""
        val (formatted, warning) = prettyEvidenceJson(source)
        assertEquals(null, warning)
        assertTrue(formatted.contains("\n    \"name\": \"QA\""))
        assertTrue(formatted.contains("\n            \"passed\": true"))
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(source),
            kotlinx.serialization.json.Json.parseToJsonElement(formatted))
    }

    @Test
    fun invalidJsonRemainsReadableWithAnExplicitWarning() {
        val source = """{"steps":["""
        val (formatted, warning) = prettyEvidenceJson(source)
        assertEquals(source, formatted)
        assertTrue(warning.orEmpty().contains("invalid or truncated"))
    }

    @Test
    fun markdownRendersStructureAndTablesButEscapesEmbeddedHtml() {
        val html = evidenceMarkdownHtml("""
            # QA report

            **Passed**

            - Open project
            - Check drawing

            ```json
            {"passed":true}
            ```

            | Step | Status |
            | --- | --- |
            | Open | Pass |

            <script>alert('unsafe')</script>

            [Unsafe](javascript:alert)
        """.trimIndent())
        assertTrue(html.contains("<h1>QA report</h1>"))
        assertTrue(html.contains("<strong>Passed</strong>"))
        assertTrue(html.contains("<li>Open project</li>"))
        assertTrue(html.contains("<pre><code"))
        assertTrue(html.contains("<table>"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("href=\"javascript:"))
    }

    @Test
    fun groupsNewestFirstByLocalCalendarDayAcrossMidnightAndDaylightSaving() {
        fun entry(id: String, timestamp: String) = QaEvidence(
            id, "$id.md", "text/markdown", Instant.parse(timestamp).toEpochMilli(),
            "reports", EvidenceSource.StoredFile(java.io.File("$id.md"))
        )
        val beforeMidnight = entry("previous-day", "2026-11-01T06:59:00Z")
        val afterMidnight = entry("midnight", "2026-11-01T07:01:00Z")
        val firstOneAm = entry("first-1am", "2026-11-01T08:30:00Z")
        val repeatedOneAm = entry("repeated-1am", "2026-11-01T09:30:00Z")
        val grouped = groupEvidenceByDay(
            listOf(beforeMidnight, firstOneAm, afterMidnight, repeatedOneAm),
            ZoneId.of("America/Los_Angeles")
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31)), grouped.keys.toList())
        assertEquals(listOf(repeatedOneAm, firstOneAm, afterMidnight), grouped.values.first())
        assertEquals(listOf(beforeMidnight), grouped.values.last())
        assertTrue(groupEvidenceByDay(emptyList()).isEmpty())
    }

    @Test
    fun listsOnlyApprovedEvidenceAndRejectsRemovedFiles() {
        val directory = Files.createTempDirectory("qa-evidence-test").toFile()
        try {
            val reports = directory.resolve("reports").apply { mkdir() }
            val report = reports.resolve("qa.md").apply { writeText("# QA") }
            reports.resolve("token.secret").writeText("not evidence")
            directory.resolve("private.json").writeText("{}")
            val files = EvidenceFiles(listOf(reports))
            assertEquals(listOf(QaEvidenceNames.stored("qa.md", report.canonicalPath)), files.list().map { it.name })
            assertEquals(report.canonicalFile, files.checked(report))
            assertFailsWith<IllegalArgumentException> { files.checked(directory.resolve("private.json")) }
            assertTrue(report.delete())
            assertFailsWith<IllegalArgumentException> { files.checked(report) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymlinkEscapesEvenWhenExtensionIsAllowed() {
        val directory = Files.createTempDirectory("qa-evidence-link-test").toFile()
        try {
            val reports = directory.resolve("reports").apply { mkdir() }
            val private = directory.resolve("private.json").apply { writeText("{}") }
            val link = reports.resolve("report.json")
            Files.createSymbolicLink(link.toPath(), private.toPath())
            val files = EvidenceFiles(listOf(reports))
            assertTrue(files.list().isEmpty())
            assertFailsWith<IllegalArgumentException> { files.checked(link) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingFoldersAreEmptyButInvalidFoldersFailExplicitly() {
        val directory = Files.createTempDirectory("qa-evidence-folder-test").toFile()
        try {
            assertTrue(EvidenceFiles(listOf(directory.resolve("absent"))).list().isEmpty())
            val invalid = directory.resolve("reports").apply { writeText("not a folder") }
            assertFailsWith<IllegalStateException> { EvidenceFiles(listOf(invalid)).list() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun textPreviewIsInertBoundedAndReportsTruncation() {
        val file = Files.createTempFile("qa-preview-test", ".md").toFile()
        try {
            file.writeText("<script>not executable</script>")
            assertEquals("<script>not executable</script>", readEvidenceText(file))
            file.writeText("a".repeat(256 * 1024 + 30))
            val preview = readEvidenceText(file)
            assertTrue(preview.startsWith("a".repeat(256 * 1024)))
            assertTrue(preview.contains("[Preview limited to 256 KiB."))
        } finally {
            file.delete()
        }
    }

    @Test
    fun mimeAllowlistIncludesSavedMediaButNotExecutableFiles() {
        assertEquals("video/mp4", EvidenceFiles.mime("recording.MP4"))
        assertEquals("image/jpeg", EvidenceFiles.mime("photo.jpg"))
        assertEquals("application/xml", EvidenceFiles.mime("junit.xml"))
        assertEquals(null, EvidenceFiles.mime("page.html"))
        assertEquals(null, EvidenceFiles.mime("app.apk"))
        assertTrue(isEvidenceText("text/markdown"))
        assertTrue(isEvidenceText("application/json"))
        assertFalse(isEvidenceText("image/jpeg"))
    }

    @Test
    fun evidenceNamesAreShortAndStepNamesRemainReproducible() {
        val compact = QaEvidenceNames.compact("r", "very-long-run-identifier:markdown", "md")
        assertTrue(compact.length <= 18)
        assertEquals(compact, QaEvidenceNames.compact("r", "very-long-run-identifier:markdown", "md"))
        assertTrue(QaEvidenceNames.stored("qa-20260101-120000-0123456789abcdef.md").startsWith("f-"))
        assertEquals("s01-0123abcd.txt", QaEvidenceNames.stored("s01-0123abcd.txt"))
        assertTrue(QaEvidenceNames.stored("mcp_1760000000000.jpg").length < "mcp_1760000000000.jpg".length)
        assertEquals("s03-0123abcd.jpg", qaStepScreenshotName(3, "0123ABCD"))
        assertEquals("steps-0123abcd.txt", QaEvidenceNames.stored("steps-qa-20261101-120000-0123abcd.txt"))
    }

    @Test
    fun updatesOneNumberedSessionFileAndRetainsItsScreenshot() {
        val directory = Files.createTempDirectory("qa-session-evidence-test").toFile()
        try {
            val trace = directory.resolve("steps-qa-20261101-120000-0123abcd.txt")
            val photo = directory.resolve(qaStepScreenshotName(2, "0123abcd"))
            fun step(number: Int, title: String, screenshot: String? = null, error: String? = null) =
                QaSessionManager.Step(number, title, "Expected $number", "Actual $number", "pass",
                    "Note $number", "2026-11-01T12:00:00Z", trace.name, screenshot,
                    screenshot != null || error != null, error,
                    if (error != null) "capture_failed" else null,
                    if (screenshot != null) "2026-11-01T12:00:00-07:00" else null,
                    "com.example.app", false, null)
            val first = step(1, "Open Project")
            writeQaStepEvidence(trace, qaSessionEvidenceText("Drawings", 1, "start", listOf(first)), null, null)
            val second = step(2, "Tap Drawings", photo.name)
            val image = byteArrayOf(1, 2, 3)
            writeQaStepEvidence(trace, qaSessionEvidenceText("Drawings", 1, "start", listOf(first, second)), photo, image)
            val third = step(3, "Open Drawing", error = "Secure window")
            writeQaStepEvidence(trace, qaSessionEvidenceText("Drawings", 1, "start", listOf(first, second, third)), null, null)
            val text = readEvidenceText(trace)
            assertEquals(listOf("1. Open Project", "2. Tap Drawings", "3. Open Drawing"),
                text.lines().filter { it.matches(Regex("[0-9]+\\. .*")) })
            assertTrue(text.contains("Expected: Expected 2"))
            assertTrue(text.contains("Actual: Actual 2"))
            assertTrue(text.contains("Screenshot: ${photo.name}"))
            assertTrue(text.contains("Screenshot: not captured (Secure window)"))
            assertTrue(photo.readBytes().contentEquals(image))
            assertEquals(setOf(QaEvidenceNames.stored(trace.name), photo.name),
                EvidenceFiles(listOf(directory)).list().map { it.name }.toSet())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedUpdatePreservesPreviousTraceAndRemovesNewPhotoAndTemporaryFiles() {
        val directory = Files.createTempDirectory("qa-session-evidence-failure-test").toFile()
        try {
            val trace = directory.resolve("steps.txt").apply { writeText("1. Open Project") }
            assertFailsWith<java.io.IOException> {
                writeQaEvidenceFile(trace) { temp ->
                    temp.writeText("partial")
                    throw java.io.IOException("Disk full")
                }
            }
            assertEquals("1. Open Project", trace.readText())
            assertFalse(directory.resolve("steps.txt.tmp").exists())
            val invalidTrace = directory.resolve("blocked.txt").apply { mkdir() }
            val photo = directory.resolve("s02-0123abcd.jpg")
            assertFailsWith<java.io.IOException> {
                writeQaStepEvidence(invalidTrace, "2. Tap Drawings", photo, byteArrayOf(1))
            }
            assertFalse(photo.exists())
            assertFalse(directory.resolve("blocked.txt.tmp").exists())
            assertFalse(directory.resolve("${photo.name}.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun explicitlySavedScreenshotIsListedAsImageEvidence() {
        val directory = Files.createTempDirectory("qa-screenshot-evidence-test").toFile()
        try {
            val photo = directory.resolve("screenshot-synthetic.jpg")
            writeQaEvidenceFile(photo) { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val evidence = EvidenceFiles(listOf(directory)).list().single()
            assertEquals("image/jpeg", evidence.mime)
            assertEquals(photo.canonicalFile, (evidence.source as EvidenceSource.StoredFile).file.canonicalFile)
            assertFalse(directory.resolve("${photo.name}.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun downloadCopiesTheCompleteBinaryFileAndClosesTheDestination() {
        val file = Files.createTempFile("qa-download-test", ".jpg").toFile()
        try {
            val content = ByteArray(300 * 1024) { (it % 256).toByte() }
            file.writeBytes(content)
            var closed = false
            val output = object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
            writeEvidenceDownload(file) { output }
            assertTrue(output.toByteArray().contentEquals(content))
            assertTrue(closed)
            assertTrue(file.readBytes().contentEquals(content))
        } finally {
            file.delete()
        }
    }

    @Test
    fun downloadSurfacesDestinationFailuresAndPreservesTheOriginal() {
        val file = Files.createTempFile("qa-download-failure-test", ".txt").toFile()
        try {
            file.writeText("Original evidence")
            assertFailsWith<IllegalStateException> { writeEvidenceDownload(file) { null } }
            var closed = false
            val output = object : OutputStream() {
                override fun write(value: Int) { throw IOException("Destination full") }
                override fun close() { closed = true }
            }
            assertFailsWith<IOException> { writeEvidenceDownload(file) { output } }
            assertTrue(closed)
            assertEquals("Original evidence", file.readText())
        } finally {
            file.delete()
        }
    }
}
