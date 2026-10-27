package com.vlad.mcpserver.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.*
import platform.Foundation.NSBundle
import platform.Foundation.*
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIScreen
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIDeviceOrientation

/**
 * Reads the state a QA engineer needs to record on iOS. There is no
 * AccessibilityService equivalent; UI tree and gestures belong to XCUITest.
 */
class IosDeviceStateReader {

    @OptIn(ExperimentalForeignApi::class)
    fun readText(): String {
        val device = UIDevice.currentDevice
        val screen = UIScreen.mainScreen
        val bounds = screen.bounds.useContents { "${size.width.toInt()}x${size.height.toInt()}" }
        val scale = screen.scale
        val style = screen.traitCollection.userInterfaceStyle
        val audio = AVAudioSession.sharedInstance()
        val appVersion = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        val version = NSProcessInfo.processInfo.operatingSystemVersionString
        return buildString {
            appendLine("Platform: iOS ${device.systemVersion} (${version})")
            appendLine("Device: ${device.name} / ${device.model}")
            appendLine("App: ${NSBundle.mainBundle.bundleIdentifier ?: "unknown"} $appVersion")
            appendLine("Locale: ${NSLocale.currentLocale.localeIdentifier()}")
            appendLine("Dark mode: ${darkModeName(style)}")
            appendLine("Orientation: ${orientationName(device.orientation)}")
            appendLine("Display: $bounds @ ${scale}x")
            appendLine("Output volume: ~${(audio.outputVolume * 100).toInt()}%")
            appendLine("Other audio playing: ${audio.isOtherAudioPlaying()}")
            appendLine("VoiceOver: ${UIAccessibilityIsVoiceOverRunning()}")
            append("Foreground app: host app (iOS cannot inspect other apps)")
        }
    }

    private fun darkModeName(style: UIUserInterfaceStyle): String = when (style) {
        UIUserInterfaceStyle.UIUserInterfaceStyleDark -> "on"
        UIUserInterfaceStyle.UIUserInterfaceStyleLight -> "off"
        UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified -> "unspecified"
        else -> "unknown"
    }

    private fun orientationName(orientation: UIDeviceOrientation): String = when (orientation) {
        UIDeviceOrientation.UIDeviceOrientationPortrait -> "portrait"
        UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> "reverse_portrait"
        UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> "landscape_left"
        UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> "landscape_right"
        else -> "unknown"
    }
}
