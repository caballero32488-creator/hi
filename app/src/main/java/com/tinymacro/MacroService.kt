package com.tinymacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MacroService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Injects one gesture; returns true if it completed. */
    suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        suspendCancellableCoroutine { c ->
            val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms.coerceIn(1, 60_000))).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!ok && c.isActive) c.resume(false)
        }

    /** Replays a multi-point finger path (curves, scrolls). pts = [x, y, msSinceDown]. */
    suspend fun gesturePath(pts: List<List<Int>>, sx: Float, sy: Float, ms: Long): Boolean =
        suspendCancellableCoroutine { c ->
            val p = Path().apply { moveTo(pts[0][0] * sx, pts[0][1] * sy); pts.drop(1).forEach { lineTo(it[0] * sx, it[1] * sy) } }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms.coerceIn(1, 60_000))).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!ok && c.isActive) c.resume(false)
        }

    companion object { @Volatile var instance: MacroService? = null }
}
