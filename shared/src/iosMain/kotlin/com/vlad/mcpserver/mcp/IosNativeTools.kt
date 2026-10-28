package com.vlad.mcpserver.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.fileDataRepresentation
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileCreationDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class IosFileAccess {

    fun read(path: String): ToolResult {
        if (path.isBlank()) return ToolResult("Missing required argument: path", isError = true)
        val resolved = NSURL.fileURLWithPath(path).URLByResolvingSymlinksInPath?.path ?: path
        val roots = sandboxRoots()
        val allowed = roots.any { root ->
            resolved == root || resolved.startsWith(root.trimEnd('/') + "/")
        }
        if (!allowed) {
            return ToolResult(
                "Path is outside the iOS app sandbox. Allowed roots: ${roots.joinToString(", ")}",
                isError = true
            )
        }
        if (!NSFileManager.defaultManager.fileExistsAtPath(resolved)) {
            return ToolResult("File not found: $resolved", isError = true)
        }
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(resolved, null)
        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (size > MAX_FILE_BYTES) {
            return ToolResult("File is too large (max ${MAX_FILE_BYTES / 1024} KB).", isError = true)
        }
        val data = NSData.dataWithContentsOfFile(resolved)
            ?: return ToolResult("Unable to read $resolved", isError = true)
        if (data.length > MAX_FILE_BYTES.toULong()) {
            return ToolResult("File is too large (max ${MAX_FILE_BYTES / 1024} KB).", isError = true)
        }
        val text = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            ?: return ToolResult("File is not valid UTF-8 text: $resolved", isError = true)
        return ToolResult("File: $resolved (${data.length} bytes)\n\n$text")
    }

    fun documentsDirectory(): String {
        val path = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        return path ?: "."
    }

    private fun sandboxRoots(): List<String> = buildList {
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull()?.let { add(it as String) }
        NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true)
            .firstOrNull()?.let { add(it as String) }
        add(NSTemporaryDirectory())
    }.map { it.trimEnd('/') }.distinct()

    private companion object {
        const val MAX_FILE_BYTES = 64L * 1024L
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosAppInfo {

    fun version(packageName: String?): ToolResult {
        val bundle = NSBundle.mainBundle
        val bundleId = bundle.bundleIdentifier ?: "unknown"
        if (!packageName.isNullOrBlank() && packageName != bundleId) {
            return ToolResult(
                "iOS does not allow inspecting other apps' bundles. This tool is limited to " +
                    "Tester ($bundleId) or an allowlisted own app.",
                isError = true
            )
        }
        val version = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        val build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
        val path = bundle.bundlePath
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val installed = (attributes?.get(NSFileCreationDate) as? NSDate)?.let(::format)
        val updated = (attributes?.get(NSFileModificationDate) as? NSDate)?.let(::format)
        return ToolResult(
            "Bundle: $bundleId\nVersion: ${version ?: "unknown"} (build ${build ?: "unknown"})\n" +
                "Path: $path\nInstalled: ${installed ?: "unknown"}\nUpdated: ${updated ?: "unknown"}"
        )
    }

    private fun format(date: NSDate): String =
        NSDateFormatter().apply { dateFormat = "yyyy-MM-dd'T'HH:mm:ssXXX" }.stringFromDate(date)
}

@OptIn(ExperimentalForeignApi::class)
class IosNotificationController {

    suspend fun post(title: String, message: String): ToolResult {
        if (title.isBlank()) return ToolResult("Missing required argument: title", isError = true)
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val granted = CompletableDeferred<Boolean>()
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound
        ) { allowed, _ -> granted.complete(allowed) }
        val allowed = withTimeoutOrNull(15_000L) { granted.await() } ?: false
        if (!allowed) {
            return ToolResult(
                "Notification permission was not granted. Enable notifications for Tester in iOS Settings.",
                isError = true
            )
        }
        val content = UNMutableNotificationContent()
        content.setTitle(title.take(100))
        content.setBody(message.take(500))
        val identifier = "tester-${NSDate().timeIntervalSince1970.toLong()}"
        val request = UNNotificationRequest.requestWithIdentifier(identifier, content, null)
        val published = CompletableDeferred<NSError?>()
        center.addNotificationRequest(request) { error -> published.complete(error) }
        val error = withTimeoutOrNull(10_000L) { published.await() }
        return if (error == null) {
            ToolResult("Notification posted with id $identifier: ${title.take(100)}")
        } else {
            ToolResult("Unable to post notification: ${error.localizedDescription}", isError = true)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosCameraController(private val files: IosFileAccess) {

    suspend fun capture(): ToolResult = withContext(Dispatchers.Main) {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        if (status == AVAuthorizationStatusNotDetermined) {
            val granted = CompletableDeferred<Boolean>()
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { allowed ->
                granted.complete(allowed)
            }
            val allowed = withTimeoutOrNull(15_000L) { granted.await() } ?: false
            if (!allowed) {
                return@withContext ToolResult(
                    "Camera permission was not granted. Enable Camera for Tester in iOS Settings.",
                    isError = true
                )
            }
        } else if (status != AVAuthorizationStatusAuthorized) {
            val reason = when (status) {
                AVAuthorizationStatusDenied -> "denied"
                AVAuthorizationStatusRestricted -> "restricted"
                else -> "unavailable"
            }
            return@withContext ToolResult("Camera access is $reason.", isError = true)
        }

        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?: return@withContext ToolResult("No camera is available on this device/simulator.", isError = true)
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
            ?: return@withContext ToolResult("Unable to open the camera input.", isError = true)
        val session = AVCaptureSession()
        val output = AVCapturePhotoOutput()
        if (!session.canAddInput(input) || !session.canAddOutput(output)) {
            return@withContext ToolResult("The camera session configuration is not supported.", isError = true)
        }
        session.addInput(input)
        session.addOutput(output)
        val completed = CompletableDeferred<NSData?>()
        val delegate = object : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
            override fun captureOutput(
                output: AVCapturePhotoOutput,
                didFinishProcessingPhoto: AVCapturePhoto,
                error: NSError?
            ) {
                completed.complete(
                    if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null
                )
            }
        }
        session.startRunning()
        output.capturePhotoWithSettings(AVCapturePhotoSettings(), delegate)
        val data = withTimeoutOrNull(15_000L) { completed.await() }
        session.stopRunning()
        if (data == null) {
            return@withContext ToolResult("Camera capture failed or timed out.", isError = true)
        }
        val file = "${files.documentsDirectory()}/tester-photo-${NSDate().timeIntervalSince1970.toLong()}.jpg"
        if (!data.writeToFile(file, atomically = true)) {
            return@withContext ToolResult("Unable to save the photo to $file.", isError = true)
        }
        ToolResult(
            text = "Photo captured: $file (${data.length} bytes)",
            imageBase64 = data.base64EncodedStringWithOptions(0u),
            imageMimeType = "image/jpeg"
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosHealthCheck(private val appInfo: IosAppInfo, private val files: IosFileAccess) {

    fun read(): String {
        val bundleId = NSBundle.mainBundle.bundleIdentifier ?: "unknown"
        val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        val camera = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> "authorized"
            AVAuthorizationStatusDenied -> "denied"
            AVAuthorizationStatusRestricted -> "restricted"
            else -> "not_determined"
        }
        val uptime = platform.Foundation.NSProcessInfo.processInfo.systemUptime.toLong()
        return buildString {
            appendLine("Platform: iOS")
            appendLine("App: $bundleId ${version ?: "unknown"}")
            appendLine("Server version: ${McpDispatcher.SERVER_VERSION}")
            appendLine("Device uptime: ${uptime}s")
            appendLine("Foreground: ${UIApplication.sharedApplication.applicationState}")
            appendLine("Camera permission: $camera")
            appendLine("Documents: ${files.documentsDirectory()}")
            append("Audio: use audio_state for TTS/volume details.")
        }
    }
}
