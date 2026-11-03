package com.vlad.tester.desktop

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalDiscoveryTest {
    @Test
    fun discoversWithInstalledMacToolsWithoutMutatingDevices() {
        assumeTrue(System.getenv("TESTER_DESKTOP_LIVE_DISCOVERY") == "1")
        val discovery = DeviceManager().discover()
        assertTrue(discovery.warnings.isEmpty(), discovery.warnings.joinToString("\n"))
        assertTrue(discovery.devices.map { it.key }.distinct().size == discovery.devices.size)
        assertTrue(discovery.devices.filter { it.platform == Platform.IOS_DEVICE }.none { it.canInstall })
        println("Live discovery: " + Platform.entries.joinToString { platform ->
            "${platform.label}=${discovery.devices.count { it.platform == platform }}"
        })
    }
}
