package com.vlad.tester.desktop

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopActivityTest {
    @Test
    fun categoryIsExplicitAndDisplayKeepsTimestampAndMessage() {
        val entry = DesktopActivity(ActivityKind.ERROR, "Cannot enable receiver", LocalTime.of(9, 12, 34))
        assertEquals(ActivityKind.ERROR, entry.kind)
        assertEquals("09:12:34  Cannot enable receiver", entry.displayText)
        assertNotEquals(ActivityKind.BRIDGE_CONNECT.icon, ActivityKind.BRIDGE_DISCONNECT.icon)
        assertEquals(ActivityKind.entries.size, ActivityKind.entries.map { it.label }.distinct().size)
    }

    @Test
    fun recordingKeepsNewestFirstAndBoundsTo100Entries() {
        var entries = emptyList<DesktopActivity>()
        repeat(105) { entries = entries.record(DesktopActivity(ActivityKind.INFO, "Entry $it")) }
        assertEquals(100, entries.size)
        assertEquals("Entry 104", entries.first().message)
        assertEquals("Entry 5", entries.last().message)
    }
}
