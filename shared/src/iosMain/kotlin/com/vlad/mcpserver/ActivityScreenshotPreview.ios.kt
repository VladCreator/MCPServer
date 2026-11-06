package com.vlad.mcpserver

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSData
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun loadActivityScreenshotPreview(artifactId: String): ImageBitmap? {
	val documents = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
		.firstOrNull() as? String) ?: return null
	val path = "$documents/qa/evidence/$artifactId"
	val data = NSData.dataWithContentsOfFile(path) ?: return null
	val length = data.length.toInt()
	if (length <= 0) return null
	val bytes = ByteArray(length)
	bytes.usePinned { pinned ->
		memcpy(pinned.addressOf(0), data.bytes, length.convert())
	}
	return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

