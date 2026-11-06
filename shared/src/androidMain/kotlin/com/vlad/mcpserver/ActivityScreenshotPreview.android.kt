package com.vlad.mcpserver

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

internal actual suspend fun loadActivityScreenshotPreview(artifactId: String): ImageBitmap? {
    if (!isAppContextInitialized()) return null
    val file = File(appContext.filesDir, "qa/evidence/$artifactId")
    if (!file.isFile) return null
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    while (bounds.outWidth / options.inSampleSize > 640 || bounds.outHeight / options.inSampleSize > 640) {
        options.inSampleSize *= 2
    }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}


