package com.vlad.mcpserver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean = withContext(Dispatchers.Main) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
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
