package com.vlad.mcpserver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardDrawerTest {
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
