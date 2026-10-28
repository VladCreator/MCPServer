package com.vlad.mcpserver.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSLock
import platform.Foundation.base64EncodedStringWithOptions
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UISwitch
import platform.UIKit.UITextField
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.accessibilityLabel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * White-box QA SDK reference for our own iOS apps.
 *
 * Unlike the Android accessibility tools, this reads the host app's own UIKit
 * view tree, captures its own window and exposes an in-memory log buffer. It
 * cannot inspect another app and is not a replacement for XCUITest.
 */
object IosQaLog {
    private val lock = NSLock()
    private val lines = ArrayDeque<String>()

    fun add(message: String) {
        lock.lock()
        try {
            lines.addLast(message.take(1_000))
            while (lines.size > MAX_LINES) lines.removeFirst()
        } finally {
            lock.unlock()
        }
    }

    fun read(query: String?, maxLines: Int): String {
        lock.lock()
        try {
            val filtered = lines.filter { line ->
                query.isNullOrBlank() || line.contains(query, ignoreCase = true)
            }.takeLast(maxLines.coerceIn(1, MAX_LINES))
            return if (filtered.isEmpty()) "(no log lines)" else filtered.joinToString("\n")
        } finally {
            lock.unlock()
        }
    }

    private const val MAX_LINES = 500
}

@OptIn(ExperimentalForeignApi::class)
class IosWhiteBox {

    fun screenTree(maxNodes: Int): ToolResult {
        val window = keyWindow() ?: return ToolResult(
            "No key window is available for the Tester app.",
            isError = true
        )
        val limit = maxNodes.coerceIn(1, 1_000)
        val out = StringBuilder()
        var count = 0
        val bounds = window.bounds.useContents { "${size.width.toInt()}x${size.height.toInt()}" }
        out.appendLine("Own app view tree (iOS white-box)")
        out.appendLine("Window: $bounds")
        fun visit(view: UIView, depth: Int) {
            if (count >= limit || view.isHidden()) return
            count += 1
            val frame = view.frame.useContents {
                "[${origin.x.toInt()},${origin.y.toInt()}][${(origin.x + size.width).toInt()},${(origin.y + size.height).toInt()}]"
            }
            val label = when (view) {
                is UILabel -> view.text
                is UIButton -> view.titleForState(UIControlStateNormal)
                is UITextField -> view.text
                is UISwitch -> if (view.isOn()) "on" else "off"
                is UIImageView -> view.accessibilityLabel()
                else -> null
            }
            val desc = view.accessibilityLabel()
            val id = (view as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier()
            val classLabel = view.toString().substringBefore('@').substringAfterLast('.')
            out.append("  ".repeat(depth))
            out.append(classLabel)
            label?.takeIf { it.isNotBlank() }?.let { out.append(" \"").append(it.take(120)).append('"') }
            desc?.takeIf { it.isNotBlank() && it != label }?.let { out.append(" desc=\"").append(it.take(120)).append('"') }
            id?.takeIf { it.isNotBlank() }?.let { out.append(" id=").append(it) }
            out.append(" bounds=").append(frame)
            if (!view.isUserInteractionEnabled()) out.append(" disabled")
            out.appendLine()
            view.subviews.forEach { child ->
                if (child is UIView) visit(child, depth + 1)
            }
        }
        visit(window, 0)
        if (count == 0) out.appendLine("(no UIKit subviews; Compose/SwiftUI may expose a single host view)")
        return ToolResult(out.toString().trimEnd())
    }

    suspend fun screenshot(maxDimension: Int): ToolResult = withContext(Dispatchers.Main) {
        val window = keyWindow() ?: return@withContext ToolResult(
            "No key window is available for the Tester app.",
            isError = true
        )
        val bounds = window.bounds
        val width = bounds.useContents { size.width }
        val height = bounds.useContents { size.height }
        val original = UIGraphicsImageRenderer(size = CGSizeMake(width, height)).imageWithActions { _ ->
            window.drawViewHierarchyInRect(bounds, afterScreenUpdates = true)
        }
        val screenWidth = width
        val screenHeight = height
        val scale = minOf(1.0, maxDimension.toDouble() / max(screenWidth, screenHeight))
        val image = if (scale < 1.0) {
            val width = (screenWidth * scale).roundToInt().toDouble()
            val height = (screenHeight * scale).roundToInt().toDouble()
            UIGraphicsImageRenderer(size = CGSizeMake(width, height)).imageWithActions { _ ->
                original.drawInRect(CGRectMake(0.0, 0.0, width, height))
            }
        } else {
            original
        }
        val data = UIImageJPEGRepresentation(image, 0.8)
            ?: return@withContext ToolResult("Unable to encode the screenshot.", isError = true)
        ToolResult(
            text = "Own-app screenshot ${image.size.useContents { "${this.width.toInt()}x${this.height.toInt()}" }} " +
                "(screen ${screenWidth.toInt()}x${screenHeight.toInt()}).",
            imageBase64 = data.base64EncodedStringWithOptions(0u),
            imageMimeType = "image/jpeg"
        )
    }

    fun logs(arguments: JsonObject): ToolResult {
        val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull
        val maxLines = (arguments["lines"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 200
        val packageName = (arguments["package_name"] as? JsonPrimitive)?.contentOrNull
        if (!packageName.isNullOrBlank() && packageName != "com.vlad.mcpserver") {
            return ToolResult(
                "iOS can only expose the Tester app's in-memory QA log. Other app logs require the host.",
                isError = true
            )
        }
        return ToolResult("Tester QA log:\n${IosQaLog.read(query, maxLines)}")
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.windows
            .mapNotNull { it as? UIWindow }
            .firstOrNull { it.isKeyWindow() }
            ?: UIApplication.sharedApplication.windows.mapNotNull { it as? UIWindow }.firstOrNull()
}
