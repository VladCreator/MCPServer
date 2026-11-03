package com.vlad.tester.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Platform(val label: String) {
    ANDROID("Android"), IOS_SIMULATOR("iOS simulator"), IOS_DEVICE("iOS device")
}

data class Device(val id: String, val name: String, val platform: Platform, val state: String) {
    val key: String get() = "${platform.name}:$id"
    val canInstall: Boolean get() = when (platform) {
        Platform.ANDROID -> state == "device"
        Platform.IOS_SIMULATOR -> state == "Booted"
        Platform.IOS_DEVICE -> false
    }
}

enum class BridgeState(val label: String) {
    ACTIVE("Active: tcp:8080 -> tcp:8080"),
    MISSING("Not configured"),
    CONFLICT("Port 8080 belongs to another mapping"),
    UNVERIFIED("Not verified")
}

data class ForwardMapping(val serial: String, val local: String, val remote: String)
data class Discovery(
    val devices: List<Device>,
    val warnings: List<String>,
    val bridges: Map<String, BridgeState> = emptyMap()
)
data class BuildIdentity(val packageName: String, val version: String, val build: String)
class DesktopFailure(message: String) : Exception(message)

fun interface CommandRunner {
    fun run(command: List<String>, timeoutSeconds: Long): String
}

class LocalCommands : CommandRunner {
    override fun run(command: List<String>, timeoutSeconds: Long): String {
        val output = File.createTempFile("tester-command-", ".log")
        try {
            val process = try {
                ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
            } catch (error: IOException) {
                throw DesktopFailure("Cannot start ${command.first()}: ${error.message}")
            }
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw DesktopFailure("${File(command.first()).name} timed out after $timeoutSeconds seconds. " +
                        "The device operation may have completed; inspect the device before retrying.")
                }
                if (output.length() > 2 * 1024 * 1024) {
                    throw DesktopFailure("Command output exceeded the 2 MiB limit.")
                }
                val text = output.readText()
                if (process.exitValue() != 0) {
                    throw DesktopFailure("${File(command.first()).name} failed: ${text.takeLast(4000).trim()}")
                }
                return text
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            if (!output.delete()) throw DesktopFailure("Cannot remove temporary command output: ${output.path}")
        }
    }
}

class DeviceManager(
    private val runner: CommandRunner = LocalCommands(),
    private val adb: String? = findAdb(),
    private val aapt: String? = findAapt(),
    private val xcrun: String = "/usr/bin/xcrun"
) {
    val adbPath: String? get() = adb

    fun discover(): Discovery {
        val devices = mutableListOf<Device>()
        val warnings = mutableListOf<String>()
        fun source(label: String, query: () -> List<Device>) {
            try {
                devices += query()
            } catch (error: DesktopFailure) {
                warnings += "$label: ${error.message}"
            } catch (error: IOException) {
                warnings += "$label: Cannot read device-tool output: ${error.message}"
            }
        }
        source("Android") { parseAdb(runner.run(listOf(requireAdb(), "devices", "-l"), 15)) }
        var mappings: List<ForwardMapping>? = null
        if (adb != null) {
            try {
                mappings = parseForwards(runner.run(listOf(adb, "forward", "--list"), 15))
            } catch (error: DesktopFailure) {
                warnings += "ADB bridge: ${error.message}"
            }
        }
        val bridges = devices.filter { it.platform == Platform.ANDROID }.associate { device ->
            device.key to if (device.canInstall && mappings != null) bridgeState(device.id, mappings)
                else BridgeState.UNVERIFIED
        }
        source("iOS simulators") {
            parseSimulators(runner.run(listOf(xcrun, "simctl", "list", "devices", "available", "--json"), 20))
        }
        source("Physical iOS") {
            val jsonFile = File.createTempFile("tester-devices-", ".json")
            try {
                runner.run(listOf(xcrun, "devicectl", "list", "devices", "--quiet",
                    "--timeout", "15", "--json-output", jsonFile.path), 20)
                if (jsonFile.length() > 2 * 1024 * 1024) throw DesktopFailure("Device list exceeds 2 MiB.")
                parsePhysical(jsonFile.readText())
            } finally {
                if (!jsonFile.delete()) throw DesktopFailure("Cannot remove temporary device list.")
            }
        }
        return Discovery(devices.distinctBy { it.key }.sortedWith(compareBy(
            { if (it.canInstall || it.platform == Platform.IOS_DEVICE) 0 else 1 },
            { it.platform.ordinal }, { it.name }
        )), warnings, bridges)
    }

    fun enableBridge(device: Device) {
        if (device.platform != Platform.ANDROID) throw DesktopFailure("ADB forwarding requires an Android device.")
        val tool = requireAdb()
        val ready = parseAdb(runner.run(listOf(tool, "devices", "-l"), 15))
            .singleOrNull { it.id == device.id }
        if (ready?.canInstall != true) throw DesktopFailure("Selected Android device is disconnected or unauthorized.")
        val state = bridgeState(device.id, parseForwards(runner.run(listOf(tool, "forward", "--list"), 15)))
        when (state) {
            BridgeState.CONFLICT -> throw DesktopFailure("Port 8080 is already mapped elsewhere. Existing forwarding was not changed.")
            BridgeState.MISSING -> runner.run(
                listOf(tool, "-s", device.id, "forward", "--no-rebind", "tcp:8080", "tcp:8080"), 15)
            BridgeState.ACTIVE -> Unit
            BridgeState.UNVERIFIED -> throw DesktopFailure("ADB forwarding could not be verified.")
        }
        if (bridgeState(device.id, parseForwards(runner.run(listOf(tool, "forward", "--list"), 15))) != BridgeState.ACTIVE) {
            throw DesktopFailure("ADB did not confirm the requested port forwarding.")
        }
    }

    fun boot(device: Device) {
        if (device.platform != Platform.IOS_SIMULATOR || device.state != "Shutdown") {
            throw DesktopFailure("Only a shutdown iOS simulator can be booted.")
        }
        runner.run(listOf(xcrun, "simctl", "boot", device.id), 30)
        runner.run(listOf(xcrun, "simctl", "bootstatus", device.id, "-b"), 120)
    }

    fun inspect(file: File, platform: Platform): BuildIdentity {
        return when (platform) {
            Platform.ANDROID -> {
                if (!file.isFile || !file.extension.equals("apk", ignoreCase = true)) {
                    throw DesktopFailure("Select a readable .apk file for Android.")
                }
                val tool = aapt ?: throw DesktopFailure("Android SDK build-tools (aapt2) are missing. Set ANDROID_HOME.")
                parseApk(runner.run(listOf(tool, "dump", "badging", file.canonicalPath), 30))
            }
            Platform.IOS_SIMULATOR -> {
                if (!file.isDirectory || !file.extension.equals("app", ignoreCase = true)) {
                    throw DesktopFailure("Select a simulator .app directory, not an IPA or device build.")
                }
                val plist = File(file, "Info.plist")
                if (plistValue(plist, "DTPlatformName") != "iphonesimulator") {
                    throw DesktopFailure("This .app is not an iOS simulator build.")
                }
                BuildIdentity(plistValue(plist, "CFBundleIdentifier"), plistValue(plist, "CFBundleShortVersionString"),
                    plistValue(plist, "CFBundleVersion")).also { validateIdentity(it) }
            }
            Platform.IOS_DEVICE -> throw DesktopFailure("Physical iOS installation is not supported in this release.")
        }
    }

    fun install(device: Device, file: File, approved: BuildIdentity): String {
        if (!device.canInstall) throw DesktopFailure("Device is not ready for installation.")
        val actual = inspect(file, device.platform)
        if (actual != approved) throw DesktopFailure("Build identity changed after confirmation. Select and confirm it again.")
        val fresh = when (device.platform) {
            Platform.ANDROID -> parseAdb(runner.run(listOf(requireAdb(), "devices", "-l"), 15))
            Platform.IOS_SIMULATOR -> parseSimulators(
                runner.run(listOf(xcrun, "simctl", "list", "devices", "available", "--json"), 20))
            Platform.IOS_DEVICE -> throw DesktopFailure("Physical iOS installation is deferred.")
        }.singleOrNull { it.key == device.key }
        if (fresh?.canInstall != true) throw DesktopFailure("Selected device disconnected or is no longer ready.")
        when (device.platform) {
            Platform.ANDROID -> {
                val installed = runner.run(listOf(requireAdb(), "-s", device.id, "install", "-r", file.canonicalPath), 180)
                if (installed.lineSequence().none { it.trim() == "Success" }) {
                    throw DesktopFailure("ADB did not confirm installation: ${installed.takeLast(2000)}")
                }
                val packageInfo = runner.run(listOf(requireAdb(), "-s", device.id, "shell",
                    "dumpsys", "package", actual.packageName), 20)
                verifyAndroid(packageInfo, actual)
            }
            Platform.IOS_SIMULATOR -> {
                runner.run(listOf(xcrun, "simctl", "install", device.id, file.canonicalPath), 180)
                val container = runner.run(listOf(xcrun, "simctl", "get_app_container", device.id,
                    actual.packageName, "app"), 20).trim()
                val installed = inspect(File(container), Platform.IOS_SIMULATOR)
                if (installed != actual) throw DesktopFailure("Install returned, but installed iOS build identity does not match.")
            }
            Platform.IOS_DEVICE -> throw DesktopFailure("Physical iOS installation is deferred.")
        }
        return "Verified ${actual.packageName} ${actual.version} (${actual.build}) on ${device.name}."
    }

    private fun requireAdb(): String = adb ?: throw DesktopFailure(
        "ADB not found. Install Android SDK platform-tools or set ANDROID_HOME / ANDROID_SDK_ROOT.")

    private fun plistValue(plist: File, key: String): String {
        if (!plist.isFile) throw DesktopFailure("App bundle is missing Info.plist.")
        return runner.run(listOf("/usr/bin/plutil", "-extract", key, "raw", "-o", "-", plist.canonicalPath), 10).trim()
    }

    companion object {
        private fun sdkRoots(): List<File> = listOfNotNull(
            System.getenv("ANDROID_HOME")?.let(::File), System.getenv("ANDROID_SDK_ROOT")?.let(::File),
            File(System.getProperty("user.home"), "Library/Android/sdk")
        ).distinct()

        private fun onPath(name: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath

        fun findAdb(): String? = onPath("adb") ?: sdkRoots().map { File(it, "platform-tools/adb") }
            .firstOrNull { it.isFile && it.canExecute() }?.absolutePath

        fun findAapt(): String? = onPath("aapt2") ?: sdkRoots().flatMap { root ->
            File(root, "build-tools").listFiles().orEmpty().sortedByDescending { it.name }
                .map { File(it, "aapt2") }
        }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath

        fun parseAdb(text: String): List<Device> = text.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 2 || fields[1] !in setOf("device", "offline", "unauthorized", "no")) return@mapNotNull null
            val state = if (fields[1] == "no") "no permissions" else fields[1]
            val model = fields.firstOrNull { it.startsWith("model:") }?.substringAfter("model:")?.replace('_', ' ')
            Device(fields[0], model ?: fields[0], Platform.ANDROID, state)
        }.toList()

        fun parseForwards(text: String): List<ForwardMapping> = text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size != 3) throw DesktopFailure("Invalid ADB forwarding response.")
            ForwardMapping(fields[0], fields[1], fields[2])
        }.toList()

        fun bridgeState(serial: String, mappings: List<ForwardMapping>): BridgeState {
            val port = mappings.filter { it.local == "tcp:8080" }
            return when {
                port.isEmpty() -> BridgeState.MISSING
                port.size == 1 && port[0].serial == serial && port[0].remote == "tcp:8080" -> BridgeState.ACTIVE
                else -> BridgeState.CONFLICT
            }
        }

        private fun json(text: String): JsonObject = try {
            Json.parseToJsonElement(text) as? JsonObject ?: throw DesktopFailure("Expected JSON object from device tooling.")
        } catch (error: IllegalArgumentException) {
            throw DesktopFailure("Invalid device-tool JSON: ${error.message}")
        }

        private fun JsonObject.optionalText(key: String): String? {
            val value = this[key] ?: return null
            if (value !is JsonPrimitive || !value.isString) {
                throw DesktopFailure("Device-tool JSON has an invalid $key.")
            }
            return value.content
        }

        private fun JsonObject.text(key: String): String = optionalText(key)?.takeIf { it.isNotBlank() }
            ?: throw DesktopFailure("Device-tool JSON is missing $key.")

        fun parseSimulators(text: String): List<Device> {
            val groups = json(text)["devices"] as? JsonObject ?: throw DesktopFailure("Missing simulator devices.")
            return groups.entries.filter { it.key.contains(".iOS-") }.flatMap { (_, value) ->
                (value as? JsonArray ?: throw DesktopFailure("Invalid simulator list.")).map { item ->
                    val obj = item as? JsonObject ?: throw DesktopFailure("Invalid simulator record.")
                    Device(obj.text("udid"), obj.text("name"), Platform.IOS_SIMULATOR, obj.text("state"))
                }
            }
        }

        fun parsePhysical(text: String): List<Device> {
            val result = json(text)["result"] as? JsonObject ?: throw DesktopFailure("Missing physical device result.")
            val devices = result["devices"] as? JsonArray ?: throw DesktopFailure("Missing physical device list.")
            return devices.mapNotNull { item ->
                val obj = item as? JsonObject ?: throw DesktopFailure("Invalid physical device record.")
                val props = obj["properties"] as? JsonObject
                val hardware = (props?.get("hardware") ?: obj["hardwareProperties"]) as? JsonObject
                val connection = (props?.get("connection") ?: obj["connectionProperties"]) as? JsonObject
                val state = connection?.optionalText("state") ?: connection?.optionalText("tunnelState")
                if (state != "connected") return@mapNotNull null
                if (hardware?.optionalText("platform") != "iOS") return@mapNotNull null
                val names = (props?.get("state") ?: obj["deviceProperties"]) as? JsonObject
                Device(obj.text("identifier"), names?.optionalText("name") ?: "iOS device",
                    Platform.IOS_DEVICE, "Connected - installation deferred")
            }
        }

        fun parseApk(text: String): BuildIdentity {
            val line = text.lineSequence().firstOrNull { it.startsWith("package:") }
                ?: throw DesktopFailure("Cannot read APK package identity.")
            fun field(key: String): String = Regex("\\b$key='([^']*)'").find(line)?.groupValues?.get(1)
                ?: throw DesktopFailure("APK is missing $key.")
            return BuildIdentity(field("name"), field("versionName"), field("versionCode")).also { validateIdentity(it) }
        }

        private fun validateIdentity(identity: BuildIdentity) {
            if (!identity.packageName.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+")) ||
                identity.version.isBlank() || identity.build.isBlank()) {
                throw DesktopFailure("Build contains an invalid package/version identity.")
            }
        }

        fun verifyAndroid(text: String, expected: BuildIdentity) {
            val packages = Regex("Package \\[([^]]+)]").findAll(text).map { it.groupValues[1] }.toList()
            val versions = Regex("(?m)^\\s*versionName=(.*)$").findAll(text).map { it.groupValues[1].trim() }.toList()
            val builds = Regex("\\bversionCode=([0-9]+)\\b").findAll(text).map { it.groupValues[1] }.toList()
            if (expected.packageName !in packages || versions.isEmpty() || builds.isEmpty() || versions.any { it != expected.version } ||
                builds.any { it != expected.build }) {
                throw DesktopFailure("Install returned, but installed Android version/build could not be verified.")
            }
        }
    }
}
