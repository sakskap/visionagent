package com.tradewind.visionagent.perception_exec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Feeds PerceptionStore on meaningful UI changes + exposes a gesture tap fallback.
 */
class VisionAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile internal var instance: VisionAccessibilityService? = null
        private const val TAG = "VisionService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        debounceJob?.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scheduleCapture()
        }
    }

    private fun scheduleCapture() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(200) // tame noisy bursts
            captureNow()
        }
    }

    private fun captureNow() {
        val root = rootInActiveWindow ?: return
        PerceptionStore.updateFrom(root)
        val pkg = root.packageName?.toString() ?: "?"
        Log.d(TAG, "Perception dump updated for $pkg")
    }

    /** DFS helper to find currently focused node (kept for UiBridge usage). */
    internal fun findFocused(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            try {
                val f = findFocused(c)
                if (f != null) return f
            } finally { c.recycle() }
        }
        return null
    }

    /** Tap the visual center of a node via accessibility gesture. */
    internal fun tapNodeCenter(node: AccessibilityNodeInfo, timeoutMs: Long = 800L): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val x = r.centerX().toFloat()
        val y = r.centerY().toFloat()

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var ok = false
        val latch = CountDownLatch(1)
        return try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true; latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture failed", t)
            false
        }
    }
}
