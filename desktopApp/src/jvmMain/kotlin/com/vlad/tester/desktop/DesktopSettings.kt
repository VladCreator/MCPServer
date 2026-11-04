package com.vlad.tester.desktop

import java.awt.Rectangle
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class DesktopSettings(
    val showAllDevices: Boolean = false,
    val bounds: Rectangle? = null
)

internal class DesktopSettingsStore(private val file: File = File(
    System.getProperty("user.home"), "Library/Application Support/Tester Desktop/settings.properties"
)) {
    fun load(): DesktopSettings {
        if (!file.exists()) return DesktopSettings()
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        val showAll = properties.getProperty("showAllDevices")?.toBooleanStrictOrNull()
            ?: throw IOException("Invalid Show all devices setting.")
        val bounds = if (listOf("x", "y", "width", "height").any { properties.containsKey(it) }) {
            fun number(key: String) = properties.getProperty(key)?.toIntOrNull()
                ?: throw IOException("Invalid window $key setting.")
            Rectangle(number("x"), number("y"), number("width"), number("height"))
        } else null
        return DesktopSettings(showAll, bounds)
    }

    fun save(settings: DesktopSettings) {
        Files.createDirectories(file.parentFile.toPath())
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".settings-", ".tmp")
        try {
            val properties = Properties().apply {
                setProperty("showAllDevices", settings.showAllDevices.toString())
                settings.bounds?.let {
                    setProperty("x", it.x.toString())
                    setProperty("y", it.y.toString())
                    setProperty("width", it.width.toString())
                    setProperty("height", it.height.toString())
                }
            }
            Files.newOutputStream(temporary).use { properties.store(it, "Tester Desktop display preferences") }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}

internal fun visibleWindowBounds(bounds: Rectangle?, screens: List<Rectangle>): Rectangle? {
    if (bounds == null || screens.isEmpty()) return null
    val screen = screens.firstOrNull {
        it.contains(bounds.x.toDouble(), bounds.y.toDouble()) &&
            it.contains(bounds.x.toDouble() + 100, bounds.y.toDouble() + 40)
    } ?: screens.first()
    val width = bounds.width.coerceIn(minOf(900, screen.width), screen.width)
    val height = bounds.height.coerceIn(minOf(650, screen.height), screen.height)
    return Rectangle(bounds.x.coerceIn(screen.x, screen.x + screen.width - width),
        bounds.y.coerceIn(screen.y, screen.y + screen.height - height), width, height)
}

internal fun visibleDevices(devices: List<Device>, showAll: Boolean): List<Device> =
    if (showAll) devices else devices.filter {
        when (it.platform) {
            Platform.ANDROID -> it.state in setOf("device", "unauthorized", "no permissions")
            Platform.IOS_SIMULATOR -> it.state == "Booted"
            Platform.IOS_DEVICE -> true // Discovery already includes only connected physical iOS devices.
        }
    }

internal fun visibleSelection(selected: Set<String>, devices: List<Device>, showAll: Boolean): Set<String> =
    selected.intersect(visibleDevices(devices, showAll).filter { it.canInstall }.map { it.key }.toSet())
