package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.ActionEligibility
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionEligibilityTest {
    @Test
    fun visibleEnabledControlIsEligibleWithoutRequiringClickable() {
        assertEquals(emptyList(), ActionEligibility.failures(true, true, true, true, false, "fully_visible"))
    }

    @Test
    fun disabledRemainsRejectedUnderEveryVisibilityPolicy() {
        for (policy in listOf("accessibility", "intersecting", "fully_visible")) {
            assertEquals(listOf("disabled"), ActionEligibility.failures(false, true, true, true, false, policy))
        }
    }

    @Test
    fun fullVisibilityReportsAllFailedGeometryChecks() {
        assertEquals(listOf("clipped_by_ancestor_or_viewport", "reported_clipping"),
            ActionEligibility.failures(true, true, true, false, true, "fully_visible"))
        assertEquals(emptyList(),
            ActionEligibility.failures(true, true, true, false, true, "intersecting"))
    }

    @Test
    fun hiddenOrEmptyGeometryCannotBecomeATapFallback() {
        assertEquals(listOf("not_accessibility_visible", "outside_visible_bounds"),
            ActionEligibility.failures(true, false, false, false, false, "accessibility"))
    }
}
