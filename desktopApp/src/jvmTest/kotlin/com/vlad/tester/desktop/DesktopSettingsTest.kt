package com.vlad.tester.desktop

import java.awt.Rectangle
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsTest {
    @Test
    fun connectedFilterKeepsUnauthorizedDevicesButNotOfflineOrShutdown() {
        val devices = listOf(
            Device("online", "Android", Platform.ANDROID, "device"),
            Device("authorize", "Android", Platform.ANDROID, "unauthorized"),
            Device("permission", "Android", Platform.ANDROID, "no permissions"),
            Device("offline", "Android", Platform.ANDROID, "offline"),
            Device("booted", "Simulator", Platform.IOS_SIMULATOR, "Booted"),
            Device("shutdown", "Simulator", Platform.IOS_SIMULATOR, "Shutdown"),
            Device("physical", "iPhone", Platform.IOS_DEVICE, "connected")
        )
        assertEquals(listOf("online", "authorize", "permission", "booted", "physical"),
            visibleDevices(devices, false).map { it.id })
        assertEquals(devices, visibleDevices(devices, true))
        assertEquals(setOf(devices[0].key, devices[4].key),
            visibleSelection(devices.map { it.key }.toSet() + "missing", devices, false))
        assertTrue(visibleDevices(emptyList(), false).isEmpty())
    }

    @Test
    fun displayPreferencesRoundTripAndDoNotStoreOperationalState() {
        val root = kotlin.io.path.createTempDirectory("tester-settings-").toFile()
        val file = File(root, "prefs/settings.properties")
        try {
            val store = DesktopSettingsStore(file)
            assertEquals(DesktopSettings(), store.load())
            val settings = DesktopSettings(true, Rectangle(42, 75, 1200, 850))
            store.save(settings)
            assertEquals(settings, store.load())
            store.save(settings.copy(showAllDevices = false))
            assertFalse(store.load().showAllDevices)
            assertEquals(setOf("showAllDevices", "x", "y", "width", "height"),
                java.util.Properties().apply { file.inputStream().use { load(it) } }.stringPropertyNames())
            assertEquals(listOf("settings.properties"), file.parentFile.list()!!.toList())
        } finally {
            file.delete()
            file.parentFile.delete()
            root.delete()
        }
    }

    @Test
    fun corruptAndUnwritableSettingsAreReportedToCaller() {
        val root = kotlin.io.path.createTempDirectory("tester-bad-settings-").toFile()
        val file = File(root, "settings.properties")
        try {
            file.writeText("showAllDevices=not-a-boolean")
            assertFailsWith<IOException> { DesktopSettingsStore(file).load() }
            file.writeText("showAllDevices=false\nwidth=bad\nheight=800\nx=0\ny=0")
            assertFailsWith<IOException> { DesktopSettingsStore(file).load() }
            file.writeText("showAllDevices=false\nx=0")
            assertFailsWith<IOException> { DesktopSettingsStore(file).load() }
            val blocked = DesktopSettingsStore(File(file, "settings.properties"))
            assertFailsWith<IOException> { blocked.save(DesktopSettings()) }
        } finally { file.delete(); root.delete() }
    }

    @Test
    fun restoresWithinVisibleScreensAndClampsInvalidSizes() {
        val primary = Rectangle(0, 25, 1440, 875)
        val secondary = Rectangle(-1280, 0, 1280, 800)
        assertEquals(Rectangle(100, 50, 1100, 800),
            visibleWindowBounds(Rectangle(100, 50, 1100, 800), listOf(primary, secondary)))
        assertEquals(Rectangle(340, 100, 1100, 800),
            visibleWindowBounds(Rectangle(9000, 9000, 1100, 800), listOf(primary)))
        assertEquals(Rectangle(0, 25, 900, 650),
            visibleWindowBounds(Rectangle(-9000, -9000, -1, 0), listOf(primary)))
        assertEquals(Rectangle(-1280, 0, 1280, 800),
            visibleWindowBounds(Rectangle(-1200, 10, 3000, 3000), listOf(primary, secondary)))
    }
}
