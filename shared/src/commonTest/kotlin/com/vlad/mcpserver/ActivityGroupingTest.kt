package com.vlad.mcpserver

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityGroupingTest {
    @Test
    fun groupsByLocalDayNewestFirstAcrossMidnightAndRepeatedHour() {
        fun log(id: Long, time: String) = ToolExecutionLog(
            id, Instant.parse(time).toEpochMilliseconds(), "client", "ui_find", "{}", "SUCCESS", null
        )
        val previous = log(1, "2026-11-01T06:59:00Z")
        val midnight = log(2, "2026-11-01T07:01:00Z")
        val firstHour = log(3, "2026-11-01T08:30:00Z")
        val repeatedHour = log(4, "2026-11-01T09:30:00Z")
        val groups = groupActivityByDay(
            listOf(midnight, previous, repeatedHour, firstHour),
            TimeZone.of("America/Los_Angeles")
        )
        assertEquals(listOf(LocalDate(2026, 11, 1), LocalDate(2026, 10, 31)), groups.keys.toList())
        assertEquals(listOf(repeatedHour, firstHour, midnight), groups.values.first())
        assertEquals(listOf(previous), groups.values.last())
    }

    @Test
    fun handlesEmptyHistoryAndUnknownTimestamps() {
        assertTrue(groupActivityByDay(emptyList()).isEmpty())
        val unknown = ToolExecutionLog(1, 0, "client", "ui_find", "{}", "SUCCESS", null)
        assertEquals(mapOf<LocalDate?, List<ToolExecutionLog>>(null to listOf(unknown)),
            groupActivityByDay(listOf(unknown)))
    }
}
