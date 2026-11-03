package com.vlad.tester.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceManagerTest {
    private val apkInfo = "package: name='com.example.app' versionCode='12' versionName='1.2'"
    private val identity = BuildIdentity("com.example.app", "1.2", "12")

    @Test
    fun androidDiscoveryDistinguishesAuthorizationOfflineAndEmulators() {
        val devices = DeviceManager.parseAdb("""
            List of devices attached
            usb-one device product:phone model:Pixel_9 transport_id:1
            usb-two unauthorized transport_id:2
            usb-three offline
            emulator-5554 device model:sdk_gphone64_arm64
            * daemon started successfully *
        """.trimIndent())
        assertEquals(4, devices.size)
        assertEquals("Pixel 9", devices[0].name)
        assertTrue(devices[0].canInstall)
        assertFalse(devices[1].canInstall)
        assertFalse(devices[2].canInstall)
        assertTrue(devices[3].canInstall)
    }

    @Test
    fun simulatorDiscoveryIncludesShutdownButDoesNotOfferInstallation() {
        val devices = DeviceManager.parseSimulators("""{"devices":{
            "com.apple.CoreSimulator.SimRuntime.iOS-27-0":[
                {"udid":"one","name":"iPhone","state":"Booted"},
                {"udid":"two","name":"iPad","state":"Shutdown"}],
            "com.apple.CoreSimulator.SimRuntime.watchOS-27-0":[{"udid":"watch","name":"Watch","state":"Booted"}]
        }}""")
        assertEquals(2, devices.size)
        assertTrue(devices[0].canInstall)
        assertFalse(devices[1].canInstall)
    }

    @Test
    fun physicalIosListsOnlyConnectedDevicesAndNeverEnablesInstallation() {
        val devices = DeviceManager.parsePhysical("""{"result":{"devices":[
            {"identifier":"one","properties":{"hardware":{"platform":"iOS"},
                "connection":{"state":"connected"},"state":{"name":"iPhone"}}},
            {"identifier":"two","properties":{"hardware":{"platform":"iOS"},
                "connection":{"state":"disconnected"},"state":{"name":"Old phone"}}},
            {"identifier":"three","hardwareProperties":{"platform":"iOS"},
                "connectionProperties":{"tunnelState":"connected"},"deviceProperties":{"name":"iPad"}},
            {"identifier":"four","properties":{"hardware":{"platform":"macOS"},"connection":{"state":"connected"}}}
        ]}}""")
        assertEquals(listOf("one", "three"), devices.map { it.id })
        assertTrue(devices.none { it.canInstall })
    }

    @Test
    fun invalidToolJsonAndMissingIdentityAreExplicitFailures() {
        for (text in listOf("invalid", "[]", "{}", """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-27":null}}""")) {
            assertFailsWith<DesktopFailure> { DeviceManager.parseSimulators(text) }
        }
        assertFailsWith<DesktopFailure> { DeviceManager.parsePhysical("{}") }
        assertFailsWith<DesktopFailure> { DeviceManager.parseApk("no package") }
        assertFailsWith<DesktopFailure> { DeviceManager.parseApk("package: name='bad package' versionName='1' versionCode='1'") }
    }

    @Test
    fun androidVerificationRejectsMissingAndWrongVersions() {
        assertEquals(identity, DeviceManager.parseApk(apkInfo))
        DeviceManager.verifyAndroid("Package [com.example.app] (abc):\n  versionCode=12 minSdk=35\n  versionName=1.2\n", identity)
        for (text in listOf("", "versionCode=13\nversionName=1.2", "versionCode=12\nversionName=other",
                            "Package [com.other.app]:\nversionCode=12\nversionName=1.2")) {
            assertFailsWith<DesktopFailure> { DeviceManager.verifyAndroid(text, identity) }
        }
    }

    @Test
    fun toolFailuresDoNotHideOtherPlatforms() {
        val manager = DeviceManager(CommandRunner { command, _ ->
            when {
                command.contains("simctl") -> """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-27":[{"udid":"sim","name":"iPhone","state":"Booted"}]}}"""
                else -> throw DesktopFailure("Tool unavailable")
            }
        }, adb = null, aapt = null)
        val result = manager.discover()
        assertEquals(1, result.devices.size)
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun installPinsAndroidSerialAndPassesFilePathAsOneArgument() {
        val apk = File.createTempFile("tester build ", ".apk")
        try {
            val calls = mutableListOf<List<String>>()
            val manager = DeviceManager(CommandRunner { command, _ ->
                calls += command
                when {
                    command.contains("badging") -> apkInfo
                    command.contains("devices") -> "serial-one device model:Phone\nserial-two device model:Other"
                    command.contains("install") -> "Success\n"
                    command.contains("dumpsys") -> "Package [com.example.app]:\nversionCode=12\nversionName=1.2"
                    else -> throw DesktopFailure("Unexpected command")
                }
            }, adb = "/fake/adb", aapt = "/fake/aapt2")
            val result = manager.install(Device("serial-one", "Phone", Platform.ANDROID, "device"), apk, identity)
            assertTrue(result.startsWith("Verified"))
            assertEquals(listOf("/fake/adb", "-s", "serial-one", "install", "-r", apk.canonicalPath),
                calls.single { it.contains("install") })
            assertTrue(calls.none { it.contains("uninstall") || it.contains("-d") })
        } finally {
            apk.delete()
        }
    }

    @Test
    fun changedBuildOrDisconnectedTargetNeverDispatchesInstall() {
        val apk = File.createTempFile("tester-", ".apk")
        try {
            for (changed in listOf(true, false)) {
                val calls = mutableListOf<List<String>>()
                val manager = DeviceManager(CommandRunner { command, _ ->
                    calls += command
                    if (command.contains("badging")) {
                        if (changed) apkInfo.replace("12", "13") else apkInfo
                    } else ""
                }, adb = "/fake/adb", aapt = "/fake/aapt")
                assertFailsWith<DesktopFailure> {
                    manager.install(Device("one", "Phone", Platform.ANDROID, "device"), apk, identity)
                }
                assertTrue(calls.none { it.contains("install") })
            }
        } finally {
            apk.delete()
        }
    }

    @Test
    fun iosInstallUsesExplicitSimulatorAndVerifiesInstalledBundle() {
        val app = kotlin.io.path.createTempDirectory("tester-ios-").toFile()
        val bundle = File(app, "Sample.app").apply { mkdir() }
        File(bundle, "Info.plist").writeText("test plist")
        val calls = mutableListOf<List<String>>()
        try {
            val manager = DeviceManager(CommandRunner { command, _ ->
                calls += command
                when {
                    command.contains("DTPlatformName") -> "iphonesimulator"
                    command.contains("CFBundleIdentifier") -> identity.packageName
                    command.contains("CFBundleShortVersionString") -> identity.version
                    command.contains("CFBundleVersion") -> identity.build
                    command.contains("list") -> """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-27":[{"udid":"sim-one","name":"iPhone","state":"Booted"}]}}"""
                    command.contains("get_app_container") -> bundle.canonicalPath
                    command.contains("install") -> ""
                    else -> throw DesktopFailure("Unexpected command")
                }
            })
            assertTrue(manager.install(Device("sim-one", "iPhone", Platform.IOS_SIMULATOR, "Booted"),
                bundle, identity).startsWith("Verified"))
            assertEquals(listOf("/usr/bin/xcrun", "simctl", "install", "sim-one", bundle.canonicalPath),
                calls.single { it.contains("install") })
            assertFailsWith<DesktopFailure> {
                manager.install(Device("physical", "iPhone", Platform.IOS_DEVICE, "connected"), bundle, identity)
            }
        } finally {
            File(bundle, "Info.plist").delete()
            bundle.delete()
            app.delete()
        }
    }

    @Test
    fun physicalIosBuildIsRejectedBeforeSimulatorInstall() {
        val root = kotlin.io.path.createTempDirectory("tester-device-build-").toFile()
        val bundle = File(root, "Sample.app").apply { mkdir() }
        File(bundle, "Info.plist").writeText("test plist")
        val calls = mutableListOf<List<String>>()
        try {
            val manager = DeviceManager(CommandRunner { command, _ ->
                calls += command
                "iphoneos"
            })
            assertFailsWith<DesktopFailure> { manager.inspect(bundle, Platform.IOS_SIMULATOR) }
            assertTrue(calls.none { it.contains("install") })
        } finally {
            File(bundle, "Info.plist").delete()
            bundle.delete()
            root.delete()
        }
    }

    @Test
    fun commandRunnerSurfacesExitFailuresAndTimeouts() {
        val runner = LocalCommands()
        assertEquals("ok", runner.run(listOf("/usr/bin/printf", "ok"), 5))
        assertFailsWith<DesktopFailure> { runner.run(listOf("/usr/bin/false"), 5) }
        assertFailsWith<DesktopFailure> { runner.run(listOf("/bin/sleep", "3"), 1) }
    }

    @Test
    fun bridgeStatusRequiresExactDeviceAndBothPorts() {
        val parse = DeviceManager::parseForwards
        assertEquals(BridgeState.MISSING, DeviceManager.bridgeState("one", parse("")))
        assertEquals(BridgeState.ACTIVE, DeviceManager.bridgeState("one", parse("one tcp:8080 tcp:8080\n")))
        assertEquals(BridgeState.MISSING, DeviceManager.bridgeState("one", parse("one tcp:18080 tcp:8080")))
        assertEquals(BridgeState.CONFLICT, DeviceManager.bridgeState("one", parse("two tcp:8080 tcp:8080")))
        assertEquals(BridgeState.CONFLICT, DeviceManager.bridgeState("one", parse("one tcp:8080 tcp:9000")))
        assertFailsWith<DesktopFailure> { parse("malformed") }
    }

    @Test
    fun enablingBridgePinsSerialUsesNoRebindAndVerifiesResult() {
        val calls = mutableListOf<List<String>>()
        var forwarded = false
        val manager = DeviceManager(CommandRunner { command, _ ->
            calls += command
            when {
                command.contains("devices") -> "one device model:Phone\ntwo device model:Other"
                command.contains("--list") -> if (forwarded) "one tcp:8080 tcp:8080" else ""
                command.contains("--no-rebind") -> { forwarded = true; "" }
                else -> throw DesktopFailure("Unexpected command")
            }
        }, adb = "/fake/adb")
        manager.enableBridge(Device("one", "Phone", Platform.ANDROID, "device"))
        assertEquals(listOf("/fake/adb", "-s", "one", "forward", "--no-rebind", "tcp:8080", "tcp:8080"),
            calls.single { it.contains("--no-rebind") })
        assertEquals(2, calls.count { it.contains("--list") })
    }

    @Test
    fun conflictsUnauthorizedAndUnconfirmedBridgeNeverReportSuccess() {
        for (scenario in listOf("conflict", "unauthorized", "unconfirmed", "already_active")) {
            val calls = mutableListOf<List<String>>()
            val manager = DeviceManager(CommandRunner { command, _ ->
                calls += command
                when {
                    command.contains("devices") -> "one ${if (scenario == "unauthorized") "unauthorized" else "device"}"
                    command.contains("--list") -> when (scenario) {
                        "conflict" -> "two tcp:8080 tcp:8080"
                        "already_active" -> "one tcp:8080 tcp:8080"
                        else -> ""
                    }
                    else -> ""
                }
            }, adb = "/fake/adb")
            val device = Device("one", "Phone", Platform.ANDROID, "device")
            if (scenario == "already_active") manager.enableBridge(device)
            else assertFailsWith<DesktopFailure> { manager.enableBridge(device) }
            if (scenario != "unconfirmed") assertTrue(calls.none { it.contains("--no-rebind") })
            assertTrue(calls.none { it.contains("--remove") || it.contains("--remove-all") })
        }
    }
}
