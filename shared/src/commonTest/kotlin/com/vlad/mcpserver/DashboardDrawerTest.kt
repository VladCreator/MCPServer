package com.vlad.mcpserver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import androidx.compose.material3.DrawerValue

class DashboardDrawerTest {
    @Test
    fun activityOpensLeftAndEvidenceRight() {
        assertFalse(DashboardPanel.ACTIVITY.opensRight)
        assertTrue(DashboardPanel.EVIDENCE.opensRight)
    }

    @Test
    fun feedbackFollowsActualOpenAndTargetState() {
        DashboardPanel.entries.forEach { panel ->
            assertNull(activeDashboardPanel(panel, DrawerValue.Closed, DrawerValue.Closed))
            assertEquals(panel, activeDashboardPanel(panel, DrawerValue.Closed, DrawerValue.Open))
            assertEquals(panel, activeDashboardPanel(panel, DrawerValue.Open, DrawerValue.Open))
            assertEquals(panel, activeDashboardPanel(panel, DrawerValue.Open, DrawerValue.Closed))
        }
    }

    @Test
    fun onlyLandscapeTabletsUseInlineDrawer() {
        assertTrue(useInlineDashboardDrawer(960f, 600f))
        assertTrue(useInlineDashboardDrawer(1200f, 800f))
        assertFalse(useInlineDashboardDrawer(600f, 960f))
        assertFalse(useInlineDashboardDrawer(900f, 599f))
        assertFalse(useInlineDashboardDrawer(599f, 900f))
        assertFalse(useInlineDashboardDrawer(600f, 600f))
        assertFalse(useInlineDashboardDrawer(393f, 852f))
        assertFalse(useInlineDashboardDrawer(852f, 393f))
    }
}
