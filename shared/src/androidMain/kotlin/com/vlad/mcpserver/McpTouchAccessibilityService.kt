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
 * Accessibility service used by the touch_test MCP tool.
 *
 * The user must enable "MCP Touch Service" in Android's accessibility
 * settings. The service only exposes gesture injection; it does not read or
 * modify the accessibility tree.
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

    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
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
        withTimeoutOrNull(2_000L) { result.await() } ?: false
    }

    companion object {
        @Volatile
        private var current: McpTouchAccessibilityService? = null

        fun instance(): McpTouchAccessibilityService? = current
    }
}
