package com.vlad.mcpserver

import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaEvidenceTest {
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
            assertEquals(listOf("qa.md"), files.list().map { it.name })
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
}
