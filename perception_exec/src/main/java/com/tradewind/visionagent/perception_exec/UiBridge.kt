package com.tradewind.visionagent.perception_exec

import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT

object UiBridge {

    /** Click a node by label or contentDescription (exact match preferred). */
    fun press(label: String?, contentDesc: String?, exact: Boolean = true): Boolean {
        val svc = VisionAccessibilityService.instance ?: return false
        val root = svc.rootInActiveWindow ?: return false

        val target = findFirst(root) { n ->
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            val hitLabel = !label.isNullOrBlank() && (
                    if (exact) t == label else (t?.contains(label!!, ignoreCase = true) == true)
                    )
            val hitDesc = !contentDesc.isNullOrBlank() && (
                    if (exact) d == contentDesc else (d?.contains(contentDesc!!, true) == true)
                    )
            (hitLabel || hitDesc) && n.isEnabled
        } ?: return false

        val ok = target.performAction(ACTION_CLICK) || tapNodeCenter(target)
        target.recycle()
        return ok
    }

    /** Type into the currently focused editable field. */
    fun inputText(text: String): Boolean {
        val svc = VisionAccessibilityService.instance ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val field = findFirst(root) { n ->
            n.isFocused && (n.className?.toString()?.contains("EditText", true) == true)
        } ?: return false

        val args = android.os.Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(ACTION_SET_TEXT, args)
        field.recycle()
        return ok
    }

    fun goHome(): Boolean {
        val svc = VisionAccessibilityService.instance ?: return false
        return svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun back(): Boolean {
        val svc = VisionAccessibilityService.instance ?: return false
        return svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun tapBounds(x:Int, y:Int, w:Int, h:Int, durationMs:Int = 100): Boolean {
        val svc = VisionAccessibilityService.instance ?: return false
        val cx = (x + w/2).toFloat()
        val cy = (y + h/2).toFloat()
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs.toLong())
        val g = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return svc.dispatchGesture(g, null, null)
    }

    fun swipe(x1:Int,y1:Int,x2:Int,y2:Int,durationMs:Int = 250): Boolean {
        val svc = VisionAccessibilityService.instance ?: return false
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val g = android.accessibilityservice.GestureDescription.Builder().addStroke(
            android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, durationMs.toLong())
        ).build()
        return svc.dispatchGesture(g, null, null)
    }

    fun openApp(pkg:String): Boolean {
        return try {
            val ctx = VisionAccessibilityService.instance ?: return false
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i); true
        } catch (_:Throwable){ false }
    }

    /* ---- helpers ---- */

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect(); node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tapBounds(r.left, r.top, r.width(), r.height(), 100)
    }

    private fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(n)) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { c ->
                    val got = try { walk(c) } finally { c.recycle() }
                    if (got != null) return got
                }
            }
            return null
        }
        return walk(root)
    }
}
