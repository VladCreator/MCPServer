package com.vlad.mcpserver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Accessibility service used by the touch and screen navigation MCP tools.
 *
 * The user must enable "MCP Touch Service" in Android's accessibility
 * settings. It injects gestures, reads the active window's UI tree and takes
 * screenshots only when an approved tool call asks for it.
 */
class McpTouchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    val windowGeneration = AtomicLong(System.nanoTime())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) || (event.windowId == rootInActiveWindow?.windowId && event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            ))) windowGeneration.incrementAndGet()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, durationMs)
    }

    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 350L): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(path, durationMs)
    }

    /** Dispatches [count] taps at the same point, separated by [intervalMs]. */
    suspend fun multiTap(
        x: Float,
        y: Float,
        count: Int,
        intervalMs: Long = 120L,
        durationMs: Long = 45L
    ): Boolean {
        val safeCount = count.coerceIn(2, 10)
        val safeInterval = intervalMs.coerceIn(30L, 1_000L)
        val path = Path().apply { moveTo(x, y) }
        val strokes = (0 until safeCount).map { index ->
            GestureDescription.StrokeDescription(path, index * safeInterval, durationMs)
        }
        return dispatchStrokes(strokes, (safeCount - 1) * safeInterval + durationMs)
    }

    /** Two-finger pinch; [endRadius] smaller than [startRadius] pinches in. */
    suspend fun pinch(
        centerX: Float,
        centerY: Float,
        startRadius: Float,
        endRadius: Float,
        durationMs: Long = 350L
    ): Boolean {
        val left = Path().apply {
            moveTo(centerX - startRadius, centerY)
            lineTo(centerX - endRadius, centerY)
        }
        val right = Path().apply {
            moveTo(centerX + startRadius, centerY)
            lineTo(centerX + endRadius, centerY)
        }
        val strokes = listOf(
            GestureDescription.StrokeDescription(left, 0L, durationMs),
            GestureDescription.StrokeDescription(right, 0L, durationMs)
        )
        return dispatchStrokes(strokes, durationMs)
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        dispatchStrokes(listOf(GestureDescription.StrokeDescription(path, 0L, durationMs)), durationMs)

    private suspend fun dispatchStrokes(
        strokes: List<GestureDescription.StrokeDescription>,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.Main) {
        if (strokes.isEmpty()) return@withContext false
        val builder = GestureDescription.Builder()
        strokes.forEach(builder::addStroke)
        val gesture = builder.build()
        val result = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.complete(false)
            }
        }
        if (!dispatchGesture(gesture, callback, null)) {
            result.complete(false)
        }
        withTimeoutOrNull(durationMs + 2_000L) { result.await() } ?: false
    }

    companion object {
        @Volatile
        private var current: McpTouchAccessibilityService? = null

        fun instance(): McpTouchAccessibilityService? = current
    }
}
