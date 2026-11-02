package com.vlad.mcpserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class ActivitySequenceTest {
    @Test
    fun keepsActivityInOneNewestFirstSequenceAcrossDates() {
        fun log(id: Long, timestamp: Long, flowId: Long?) = ToolExecutionLog(
            id, timestamp, "client", "ui_find", "{}", "SUCCESS", null, flowId
        )
        val olderDate = log(10, 100, 1)
        val newerDate = log(11, 200, 2)
        val sameTimeEarlierId = log(12, 200, 1)

        assertEquals(
            listOf(sameTimeEarlierId, newerDate, olderDate),
            activityLogsInSequence(listOf(olderDate, newerDate, sameTimeEarlierId))
        )
        assertEquals("Flow #2", flowLabel(newerDate.flowId))
    }

    @Test
    fun keepsUnlinkedActivityInTheSameSequence() {
        val unlinked = ToolExecutionLog(1, 0, "client", "ui_find", "{}", "SUCCESS", null)
        assertEquals(listOf(unlinked), activityLogsInSequence(listOf(unlinked)))
        assertEquals(emptyList(), activityLogsInSequence(emptyList()))
        assertEquals("Unlinked", flowLabel(unlinked.flowId))
    }

    @Test
    fun groupsByLocalDayKeepingNewestFirstAcrossMidnightAndRepeatedHour() {
        fun log(id: Long, time: String) = ToolExecutionLog(
            id, Instant.parse(time).toEpochMilliseconds(), "client", "ui_find", "{}", "SUCCESS", null
        )
        val previous = log(1, "2026-11-01T06:59:00Z")
        val midnight = log(2, "2026-11-01T07:01:00Z")
        val firstHour = log(3, "2026-11-01T08:30:00Z")
        val repeatedHour = log(4, "2026-11-01T09:30:00Z")
        val tied = log(5, "2026-11-01T09:30:00Z")
        val grouped = groupActivityByDay(
            listOf(previous, firstHour, tied, midnight, repeatedHour),
            TimeZone.of("America/Los_Angeles")
        )
        assertEquals(listOf(LocalDate(2026, 11, 1), LocalDate(2026, 10, 31)), grouped.keys.toList())
        assertEquals(listOf(tied, repeatedHour, firstHour, midnight), grouped.values.first())
        assertEquals(listOf(previous), grouped.values.last())
        assertEquals(emptyMap(), groupActivityByDay(emptyList()))
    }
}
