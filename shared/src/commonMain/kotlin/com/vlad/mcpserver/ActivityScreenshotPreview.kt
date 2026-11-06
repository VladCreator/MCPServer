package com.vlad.mcpserver

import androidx.compose.ui.graphics.ImageBitmap

internal data class ActivityScreenshotMeta(
    val displayName: String?,
    val artifactId: String?
)

private val screenshotArtifactRegex = Regex("artifact: ([^)]+)\\)")
private val screenshotDisplayRegex = Regex("Saved in QA Evidence: (.+?) \\(artifact:")

internal fun extractScreenshotMeta(log: ToolExecutionLog): ActivityScreenshotMeta? {
    if (log.toolName != "take_screenshot") return null
    val result = log.result ?: return null
    val artifact = screenshotArtifactRegex.find(result)?.groupValues?.getOrNull(1)?.trim()
    val display = screenshotDisplayRegex.find(result)?.groupValues?.getOrNull(1)?.trim()
    return if (artifact == null && display == null) null else ActivityScreenshotMeta(display, artifact)
}

internal expect suspend fun loadActivityScreenshotPreview(artifactId: String): ImageBitmap?

