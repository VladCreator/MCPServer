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
import platform.UIKit.UILabel
import platform.UIKit.UISwitch
import platform.UIKit.UITextField
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityElementAtIndex
import platform.UIKit.accessibilityElementCount
import platform.UIKit.accessibilityElements
import platform.UIKit.accessibilityFrame
import platform.UIKit.accessibilityTraits
import platform.UIKit.accessibilityValue
import platform.UIKit.isAccessibilityElement
import platform.UIKit.UIAccessibilityTraitAdjustable
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAccessibilityTraitHeader
import platform.UIKit.UIAccessibilityTraitImage
import platform.UIKit.UIAccessibilityTraitLink
import platform.UIKit.UIAccessibilityTraitNotEnabled
import platform.UIKit.UIAccessibilityTraitSearchField
import platform.UIKit.UIAccessibilityTraitSelected
import platform.UIKit.UIAccessibilityTraitStaticText
import platform.UIKit.UIControl
import platform.CoreGraphics.CGRect
import platform.Foundation.NSNotFound
import platform.darwin.NSObject
import platform.objc.class_getName
import platform.objc.object_getClass
import kotlinx.cinterop.CValue
import kotlinx.cinterop.toKString
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

    /**
     * Walks the key window on the main thread. UIKit containers without content
     * are collapsed; accessibility elements are included so Compose and SwiftUI
     * content (which lives inside a single host view) is visible too.
     */
    suspend fun screenTree(maxNodes: Int): ToolResult = withContext(Dispatchers.Main) {
        val window = keyWindow() ?: return@withContext ToolResult(
            "No key window is available for the Tester app.",
            isError = true
        )
        val limit = maxNodes.coerceIn(1, 1_000)
        val out = StringBuilder()
        var count = 0
        var truncated = false
        val visited = mutableSetOf<NSObject>()
        val bounds = window.bounds.useContents { "${size.width.toInt()}x${size.height.toInt()}" }
        out.appendLine("Own app view tree (iOS white-box)")
        out.appendLine("Window: $bounds (points)")

        fun emit(depth: Int, line: String): Boolean {
            if (count >= limit) {
                truncated = true
                return false
            }
            count += 1
            out.append("  ".repeat(depth)).appendLine(line)
            return true
        }

        fun visitElement(element: NSObject, depth: Int) {
            if (count >= limit || !visited.add(element)) return
            val children = accessibilityChildren(element)
            var childDepth = depth
            if (element.isAccessibilityElement() || describesContent(element)) {
                if (!emit(depth, describeElement(element))) return
                childDepth = depth + 1
            }
            children.forEach { visitElement(it, childDepth) }
        }

        fun visitView(view: UIView, depth: Int) {
            if (count >= limit || view.isHidden() || view.alpha <= 0.01) return
            val content = viewContent(view)
            var childDepth = depth
            if (content != null || view is UIControl || view == window) {
                val frame = rectText(view.convertRect(view.bounds, toView = null))
                val line = buildString {
                    append(className(view))
                    content?.let { append(it) }
                    append(" bounds=").append(frame)
                    if (!view.isUserInteractionEnabled()) append(" disabled")
                }
                if (!emit(depth, line)) return
                childDepth = depth + 1
            }
            accessibilityChildren(view).forEach { visitElement(it, childDepth) }
            view.subviews.forEach { child -> if (child is UIView) visitView(child, childDepth) }
        }

        visitView(window, 0)
        if (truncated) out.appendLine("… truncated at $limit nodes (raise max_nodes)")
        if (count <= 1) {
            out.appendLine("(no visible content; if this is Compose, accessibility may not be ready yet)")
        }
        ToolResult(out.toString().trimEnd())
    }

    private fun viewContent(view: UIView): String? {
        val text = when (view) {
            is UILabel -> view.text
            is UIButton -> view.titleForState(UIControlStateNormal)
            is UITextField -> view.text
            is UISwitch -> if (view.isOn()) "on" else "off"
            else -> null
        }
        val desc = view.accessibilityLabel()
        val id = (view as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier()
        if (text.isNullOrBlank() && desc.isNullOrBlank() && id.isNullOrBlank()) return null
        return buildString {
            text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it.take(120)).append('"') }
            desc?.takeIf { it.isNotBlank() && it != text }?.let { append(" desc=\"").append(it.take(120)).append('"') }
            id?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
        }
    }

    private fun describesContent(element: NSObject): Boolean =
        !element.accessibilityLabel().isNullOrBlank() ||
            !element.accessibilityValue().isNullOrBlank() ||
            !(element as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier().isNullOrBlank()

    private fun describeElement(element: NSObject): String = buildString {
        val traits = element.accessibilityTraits()
        append(roleOf(traits))
        element.accessibilityLabel()?.takeIf { it.isNotBlank() }?.let {
            append(" \"").append(it.take(120)).append('"')
        }
        element.accessibilityValue()?.takeIf { it.isNotBlank() }?.let {
            append(" value=\"").append(it.take(120)).append('"')
        }
        (element as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier()
            ?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
        append(" bounds=").append(rectText(element.accessibilityFrame()))
        if (traits and UIAccessibilityTraitSelected != 0uL) append(" selected")
        if (traits and UIAccessibilityTraitNotEnabled != 0uL) append(" disabled")
    }

    private fun roleOf(traits: ULong): String = when {
        traits and UIAccessibilityTraitButton != 0uL -> "Button"
        traits and UIAccessibilityTraitLink != 0uL -> "Link"
        traits and UIAccessibilityTraitHeader != 0uL -> "Header"
        traits and UIAccessibilityTraitSearchField != 0uL -> "SearchField"
        traits and UIAccessibilityTraitImage != 0uL -> "Image"
        traits and UIAccessibilityTraitAdjustable != 0uL -> "Adjustable"
        traits and UIAccessibilityTraitStaticText != 0uL -> "Text"
        else -> "Element"
    }

    private fun accessibilityChildren(element: NSObject): List<NSObject> {
        element.accessibilityElements()?.let { list ->
            return list.mapNotNull { it as? NSObject }
        }
        val total = element.accessibilityElementCount()
        if (total <= 0L || total == NSNotFound) return emptyList()
        return (0 until minOf(total, 1_000L)).mapNotNull {
            element.accessibilityElementAtIndex(it) as? NSObject
        }
    }

    private fun className(target: NSObject): String =
        object_getClass(target)?.let { class_getName(it)?.toKString() }?.substringAfterLast('.') ?: "UIView"

    private fun rectText(rect: CValue<CGRect>): String = rect.useContents {
        "[${origin.x.toInt()},${origin.y.toInt()}][${(origin.x + size.width).toInt()},${(origin.y + size.height).toInt()}]"
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
