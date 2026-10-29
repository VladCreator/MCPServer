package com.vlad.mcpserver

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Criteria used to locate a UI element. [index] refers to the numbering of
 * the last [ScreenNavigator.dumpTree] call; the other fields are matched
 * against the live screen (text and description are case-insensitive
 * "contains" matches, exact matches win).
 */
data class NodeSelector(
    val index: Int? = null,
    val text: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val textExact: Boolean = false,
    val className: String? = null,
    val nth: Int = 0,
    val includeInvisible: Boolean = false,
    val requireEnabled: Boolean? = null,
    val requireClickable: Boolean? = null
) {
    val isEmpty: Boolean
        get() = index == null && text == null && resourceId == null && contentDescription == null &&
            className == null

    override fun toString(): String = listOfNotNull(
        index?.let { "index=$it" },
        text?.let { if (textExact) "text_exact=\"$it\"" else "text=\"$it\"" },
        resourceId?.let { "resource_id=$it" },
        contentDescription?.let { "content_desc=\"$it\"" },
        className?.let { "class_name=$it" },
        if (nth > 0) "nth=$nth" else null
    ).joinToString(", ")
}

data class Screenshot(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val screenHeight: Int
)

/**
 * Screen reading and navigation built on top of [McpTouchAccessibilityService].
 */
object ScreenNavigator {

    @Volatile
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    /**
     * Returns a compact, indented text description of the active window.
     * Only meaningful nodes (text, description or interactive) are listed.
     */
    fun dumpTree(service: AccessibilityService, maxNodes: Int): String? {
        val root = service.rootInActiveWindow ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val metrics = service.resources.displayMetrics
        val out = StringBuilder()
        out.appendLine("Package: ${root.packageName}")
        out.appendLine("Screen: ${metrics.widthPixels}x${metrics.heightPixels}")
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (truncated || !node.isVisibleToUser) return
            var childDepth = depth
            if (isMeaningful(node)) {
                if (nodes.size >= maxNodes) {
                    truncated = true
                    return
                }
                out.append("  ".repeat(depth)).appendLine(describe(nodes.size, node))
                nodes += node
                childDepth = depth + 1
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, childDepth) }
            }
        }

        visit(root, 0)
        lastNodes = nodes
        if (nodes.isEmpty()) {
            out.appendLine("(no readable elements; try take_screenshot)")
        }
        if (truncated) {
            out.appendLine("... truncated at $maxNodes elements")
        }
        return out.toString().trimEnd()
    }

    fun find(service: AccessibilityService, selector: NodeSelector): AccessibilityNodeInfo? {
        selector.index?.let { index ->
            val node = lastNodes.getOrNull(index) ?: return null
            return if (node.refresh() && node.isVisibleToUser) node else null
        }
        val root = service.rootInActiveWindow ?: return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!selector.includeInvisible && !node.isVisibleToUser) return
            if (matches(node, selector)) candidates += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(::visit)
            }
        }
        visit(root)
        if (selector.nth > 0) return candidates.getOrNull(selector.nth)
        return candidates.firstOrNull { isExactMatch(it, selector) } ?: candidates.firstOrNull()
    }

    /**
     * Returns every visible node matching [selector], in traversal order.
     * [NodeSelector.nth] skips that many leading matches; [limit] caps the result.
     */
    fun findAll(
        service: AccessibilityService,
        selector: NodeSelector,
        limit: Int = 50,
        root: AccessibilityNodeInfo? = service.rootInActiveWindow
    ): List<AccessibilityNodeInfo> {
        selector.index?.let { index ->
            val node = lastNodes.getOrNull(index) ?: return emptyList()
            return if (node.refresh() && (selector.includeInvisible || node.isVisibleToUser)) listOf(node) else emptyList()
        }
        if (root == null) return emptyList()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!selector.includeInvisible && !node.isVisibleToUser) return
            if (matches(node, selector)) candidates += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(::visit)
            }
        }
        visit(root)
        return candidates.drop(selector.nth.coerceAtLeast(0)).take(limit.coerceIn(1, 100))
    }

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    /** True when the node is clickable or exposes ACTION_CLICK (common in MAUI/Flutter/RN views). */
    fun hasClickAction(node: AccessibilityNodeInfo, longPress: Boolean = false): Boolean {
        val action = if (longPress) AccessibilityAction.ACTION_LONG_CLICK else AccessibilityAction.ACTION_CLICK
        return (if (longPress) node.isLongClickable else node.isClickable) ||
            node.actionList.any { it.id == action.id }
    }

    /**
     * Clicks [node] and returns how it was done, or null on failure. Order: the node's own
     * click action; a clickable ancestor of about the same size; a real tap on the node's
     * center (so a non-clickable child is not "clicked" through a large container); finally
     * any clickable ancestor.
     */
    suspend fun click(
        service: McpTouchAccessibilityService,
        node: AccessibilityNodeInfo,
        longPress: Boolean
    ): String? {
        val action = if (longPress) {
            AccessibilityNodeInfo.ACTION_LONG_CLICK
        } else {
            AccessibilityNodeInfo.ACTION_CLICK
        }
        if (hasClickAction(node, longPress) && node.performAction(action)) return "accessibility action"

        var ancestor: AccessibilityNodeInfo? = node.parent
        while (ancestor != null && !hasClickAction(ancestor, longPress)) {
            ancestor = ancestor.parent
        }
        val rect = bounds(node)
        val nodeArea = rect.width().toLong() * rect.height()
        if (ancestor != null && nodeArea > 0) {
            val parentRect = bounds(ancestor)
            val parentArea = parentRect.width().toLong() * parentRect.height()
            if (parentArea <= nodeArea * SAME_SIZE_FACTOR && ancestor.performAction(action)) {
                return "clickable ancestor"
            }
        }
        if (!rect.isEmpty && service.tap(
                rect.exactCenterX(),
                rect.exactCenterY(),
                durationMs = if (longPress) LONG_PRESS_MS else TAP_MS
            )
        ) {
            return "tap gesture at (${rect.centerX()}, ${rect.centerY()})"
        }
        if (ancestor != null && ancestor.performAction(action)) return "clickable ancestor (large)"
        return null
    }

    /** Finds an editable node: the node itself, an editable descendant, or the focused input. */
    fun editableTarget(service: AccessibilityService, node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }
        }
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> editableTarget(service, child)?.let { return it } }
        }
        return null
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressImeEnter(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)

    /** Largest visible scrollable node on screen. */
    fun primaryScrollable(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            if (node.isScrollable) {
                val rect = bounds(node)
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(::visit)
            }
        }
        visit(root)
        return best
    }

    /**
     * Scrolls so that content in [direction] becomes visible ("down" reveals
     * content below). Tries accessibility actions first, then a swipe gesture.
     */
    suspend fun scroll(
        service: McpTouchAccessibilityService,
        node: AccessibilityNodeInfo?,
        direction: String
    ): String? {
        if (node != null) {
            val directional = when (direction) {
                "up" -> AccessibilityAction.ACTION_SCROLL_UP
                "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                else -> AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val sequential = if (direction == "down" || direction == "right") {
                AccessibilityAction.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityAction.ACTION_SCROLL_BACKWARD
            }
            val supported = node.actionList
            for (action in listOf(directional, sequential)) {
                if (supported.any { it.id == action.id } && node.performAction(action.id)) {
                    return "accessibility action"
                }
            }
        }

        val metrics = service.resources.displayMetrics
        val area = node?.let(::bounds)?.takeIf { !it.isEmpty }
            ?: Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val dx = area.width() * SWIPE_FRACTION / 2f
        val dy = area.height() * SWIPE_FRACTION / 2f
        // The finger moves opposite to the direction of the content we want to reveal.
        val swiped = when (direction) {
            "down" -> service.swipe(cx, cy + dy, cx, cy - dy)
            "up" -> service.swipe(cx, cy - dy, cx, cy + dy)
            "right" -> service.swipe(cx + dx, cy, cx - dx, cy)
            else -> service.swipe(cx - dx, cy, cx + dx, cy)
        }
        return if (swiped) "swipe gesture" else null
    }

    suspend fun takeScreenshot(service: AccessibilityService, maxDimension: Int): Result<Screenshot> {
        val bitmap = suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val copy = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        buffer.close()
                        cont.resume(
                            copy?.let { Result.success(it) }
                                ?: Result.failure(IllegalStateException("Unable to decode screenshot"))
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(Result.failure(IllegalStateException(screenshotError(errorCode))))
                    }
                }
            )
        }.getOrElse { return Result.failure(it) }

        val screenWidth = bitmap.width
        val screenHeight = bitmap.height
        val scale = minOf(1f, maxDimension.toFloat() / max(screenWidth, screenHeight))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (screenWidth * scale).roundToInt(),
                (screenHeight * scale).roundToInt(),
                true
            ).also { bitmap.recycle() }
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }
        val result = Screenshot(bytes, scaled.width, scaled.height, screenWidth, screenHeight)
        scaled.recycle()
        return Result.success(result)
    }

    private fun screenshotError(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            "The current screen is protected (FLAG_SECURE) and cannot be captured."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "Screenshots were requested too quickly; wait a moment and retry."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "The accessibility service is not allowed to take screenshots. Re-enable 'MCP Touch Service'."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display."
        else -> "Internal error ($code)."
    }

    private fun isMeaningful(node: AccessibilityNodeInfo): Boolean =
        !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            !node.hintText.isNullOrBlank() ||
            hasClickAction(node) ||
            node.isLongClickable ||
            node.isScrollable ||
            node.isEditable ||
            node.isCheckable

    private fun describe(index: Int, node: AccessibilityNodeInfo): String {
        val rect = bounds(node)
        return buildString {
            append('[').append(index).append("] ")
            append(node.className?.toString()?.substringAfterLast('.') ?: "View")
            if (node.isPassword) {
                append(" \"<password>\"")
            } else {
                node.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(clip(it)).append('"') }
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                append(" desc=\"").append(clip(it)).append('"')
            }
            if (node.text.isNullOrBlank()) {
                node.hintText?.takeIf { it.isNotBlank() }?.let { append(" hint=\"").append(clip(it)).append('"') }
            }
            node.viewIdResourceName?.let { append(" id=").append(it) }
            append(" bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
            val flags = buildList {
                if (node.isClickable) add("clickable") else if (hasClickAction(node)) add("click-action")
                if (node.isLongClickable) add("long-clickable")
                if (node.isScrollable) add("scrollable")
                if (node.isEditable) add("editable")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (node.isSelected) add("selected")
                if (node.isFocused) add("focused")
                if (!node.isEnabled) add("disabled")
            }
            if (flags.isNotEmpty()) append(' ').append(flags.joinToString(","))
        }
    }

    private fun clip(value: CharSequence): String =
        value.toString().replace('\n', ' ').replace("\"", "'").let {
            if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "…" else it
        }

    private fun matches(node: AccessibilityNodeInfo, selector: NodeSelector): Boolean {
        selector.resourceId?.let { id ->
            val name = node.viewIdResourceName ?: return false
            if (name != id && !name.endsWith(":id/$id")) return false
        }
        selector.text?.let { text ->
            val actual = node.text?.toString()
            val ok = if (selector.textExact) {
                actual.equals(text, ignoreCase = true)
            } else {
                actual?.contains(text, ignoreCase = true) == true
            }
            if (!ok) return false
        }
        selector.contentDescription?.let { desc ->
            if (node.contentDescription?.contains(desc, ignoreCase = true) != true) return false
        }
        selector.className?.let { name ->
            val actual = node.className?.toString() ?: return false
            if (!actual.equals(name, ignoreCase = true) &&
                !actual.substringAfterLast('.').equals(name, ignoreCase = true)
            ) return false
        }
        selector.requireEnabled?.let { if (node.isEnabled != it) return false }
        selector.requireClickable?.let { if (node.isClickable != it) return false }
        return true
    }

    fun isExactMatch(node: AccessibilityNodeInfo, selector: NodeSelector): Boolean {
        val textOk = selector.text?.let { node.text?.toString().equals(it, ignoreCase = true) } ?: true
        val descOk = selector.contentDescription?.let {
            node.contentDescription?.toString().equals(it, ignoreCase = true)
        } ?: true
        return textOk && descOk
    }

    private const val MAX_TEXT = 120
    private const val TAP_MS = 60L
    private const val LONG_PRESS_MS = 800L
    private const val SAME_SIZE_FACTOR = 2L
    private const val SWIPE_FRACTION = 0.6f
    private const val JPEG_QUALITY = 70
}
