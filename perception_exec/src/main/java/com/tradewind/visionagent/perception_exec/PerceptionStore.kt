package com.tradewind.visionagent.perception_exec

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * PerceptionStore
 * - dump: legacy plain-text tree for narration
 * - world: structured state (screenshot + full a11y tree + recent actions + fpsHint)
 */
object PerceptionStore {

    /* ---------------- legacy plain-text dump ---------------- */

    private val _dump = MutableStateFlow("")
    val dump: StateFlow<String> = _dump

    fun latestSummary(): String = _dump.value

    /**
     * Backward-compatible: updates the plain-text dump and the structured a11y snapshot.
     * Call this from your AccessibilityService when the root changes.
     */
    fun updateFrom(root: AccessibilityNodeInfo?) {
        val (dumpStr, snap) = if (root == null) "" to null else {
            buildString { serializeDump(root, this) } to serializeA11y(root)
        }
        if (dumpStr.isNotBlank() && dumpStr != _dump.value) _dump.value = dumpStr
        lastA11y = snap
        maybeEmit()
    }

    /* ---------------- world state stream ---------------- */

    private val _world = MutableSharedFlow<WorldState>(replay = 1, extraBufferCapacity = 2)
    val world: SharedFlow<WorldState> = _world

    @Volatile private var lastShot: Screenshot? = null
    @Volatile private var lastA11y: AccessibilitySnapshot? = null
    private val recent = ArrayDeque<RecentAction>(32)

    // Burst hint: temporarily raise fps after actions
    @Volatile private var burstUntilUptime = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var burstWatcherStarted = false

    /** Feed a downscaled JPEG (Q ~60–75, longest side ~1024–1280). */
    fun updateScreenshot(jpeg: ByteArray, w: Int, h: Int) {
        lastShot = Screenshot(
            id = "scr_${System.currentTimeMillis()}",
            ts = System.currentTimeMillis(),
            w = w, h = h, jpeg = jpeg
        )
        maybeEmit()
    }

    /** Record an executor action so the model sees plan→act→observe. */
    fun recordAction(a: RecentAction) {
        if (recent.size >= 32) recent.removeFirst()
        recent.addLast(a)
        requestBurst(1500L)
        maybeEmit()
    }

    /** Ask the store to advertise a temporary higher fps (for ~ms). */
    fun requestBurst(ms: Long = 1500L) {
        burstUntilUptime = SystemClock.uptimeMillis() + ms
        if (!burstWatcherStarted) {
            burstWatcherStarted = true
            scope.launch {
                while (SystemClock.uptimeMillis() < burstUntilUptime) delay(120)
                burstWatcherStarted = false
                maybeEmit() // drop back to idle fps
            }
        }
    }

    /* ---------------- emit logic ---------------- */

    private fun maybeEmit() {
        val a = lastA11y ?: return
        val fpsHint = if (SystemClock.uptimeMillis() < burstUntilUptime) 5 else 2
        _world.tryEmit(
            WorldState(
                screenshot = lastShot,
                a11y = a,
                recent = recent.toList(),
                fpsHint = fpsHint
            )
        )
    }

    /* ---------------- a11y serializers ---------------- */

    private fun serializeA11y(root: AccessibilityNodeInfo): AccessibilitySnapshot {
        val nodes = ArrayList<A11yNode>(256)

        fun bounds(n: AccessibilityNodeInfo): Bounds {
            val r = Rect(); n.getBoundsInScreen(r)
            return Bounds(r.left, r.top, r.width(), r.height())
        }

        fun walk(n: AccessibilityNodeInfo, id: String) {
            nodes += A11yNode(
                id = id,
                cls = n.className?.toString(),
                text = n.text?.toString(),
                desc = n.contentDescription?.toString(),
                resId = n.viewIdResourceName,
                b = bounds(n),
                clickable = n.isClickable,
                enabled = n.isEnabled,
                focused = n.isFocused
            )
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { c ->
                    try { walk(c, "$id.$i") } finally { c.recycle() }
                }
            }
        }

        walk(root, "n")
        return AccessibilitySnapshot(
            ts = System.currentTimeMillis(),
            app = root.packageName?.toString(),
            nodes = nodes
        )
    }

    private fun serializeDump(root: AccessibilityNodeInfo, sb: StringBuilder) {
        fun AccessibilityNodeInfo.label(): String {
            val t = text?.toString()
            val d = contentDescription?.toString()
            return when {
                !t.isNullOrBlank() -> t.take(120)
                !d.isNullOrBlank() -> d.take(120)
                else -> ""
            }
        }
        fun line(depth: Int, s: String) { repeat(depth) { sb.append("  ") }; sb.appendLine(s) }
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null) return
            val cls = n.className ?: "View"
            val id = n.viewIdResourceName ?: ""
            val flags = buildString {
                if (n.isFocused) append(" [FOCUSED]")
                if (!n.isEnabled) append(" [DISABLED]")
            }
            val lbl = n.label().replace("\n", " ").trim()
            line(depth, "$cls id=$id$flags label=\"${lbl}\"")
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
    }
}
