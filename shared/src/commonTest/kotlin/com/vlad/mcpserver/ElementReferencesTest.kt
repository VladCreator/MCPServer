package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.ElementReferences
import com.vlad.mcpserver.mcp.ObservationWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElementReferencesTest {
    private var time = 0L
    private var id = 0
    private val store = ElementReferences<String>({ time }, { (++id).toString() })
    private val window = ObservationWindow(1, 7, "fixture", 0, 100, 200)

    @Test fun referencesAreOwnedAndExpire() {
        val (_, ids) = store.record("session-a", window, listOf("button"))
        assertEquals("button", store.resolve("session-a", ids.single(), window))
        assertNull(store.resolve("session-b", ids.single(), window))
        time = 30_000
        assertNull(store.resolve("session-a", ids.single(), window))
    }

    @Test fun everyWindowDimensionIsPartOfIdentity() {
        val (_, ids) = store.record("a", window, listOf("button"))
        for (changed in listOf(window.copy(generation = 2), window.copy(windowId = 8),
                window.copy(packageName = "other"), window.copy(rotation = 1),
                window.copy(width = 200), window.copy(height = 100))) {
            assertNull(store.resolve("a", ids.single(), changed))
        }
    }

    @Test fun newObservationsAndActionsInvalidateOldReferences() {
        val first = store.record("a", window, listOf("old")).second.single()
        store.record("b", window, listOf("other"))
        assertEquals("old", store.resolve("a", first, window))
        val second = store.record("a", window, listOf("new")).second.single()
        assertNull(store.resolve("a", first, window))
        assertEquals("new", store.resolve("a", second, window))
        store.clear()
        assertNull(store.resolve("a", second, window))
    }

    @Test fun storageIsBounded() {
        val first = store.record("a", window, List(1001) { "a" }).second
        assertEquals(1000, first.size)
        repeat(5) { store.record("owner-$it", window, List(1000) { "b" }) }
        assertNull(store.resolve("a", first.first(), window))
    }
}
