package com.vlad.mcpserver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceDrawerTest {
    @Test
    fun onlyLandscapeTabletsUseInlineDrawer() {
        assertTrue(useInlineEvidenceDrawer(960f, 600f))
        assertTrue(useInlineEvidenceDrawer(1200f, 800f))
        assertFalse(useInlineEvidenceDrawer(600f, 960f))
        assertFalse(useInlineEvidenceDrawer(900f, 599f))
        assertFalse(useInlineEvidenceDrawer(599f, 900f))
        assertFalse(useInlineEvidenceDrawer(600f, 600f))
        assertFalse(useInlineEvidenceDrawer(393f, 852f))
        assertFalse(useInlineEvidenceDrawer(852f, 393f))
    }
}
