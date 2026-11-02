package com.vlad.mcpserver

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
