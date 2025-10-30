package com.tradewind.visionagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textview.MaterialTextView
import com.tradewind.visionagent.audio.LiveS2SService
import com.tradewind.visionagent.perception_exec.ScreenCaptureService

class MainActivity : ComponentActivity() {

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensureNotifThenStart() else updateStatus("Mic permission denied")
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startVoiceServiceAndScreenCapture()
        }

    private val screenCapLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val i = Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, res.data)
                try {
                    ContextCompat.startForegroundService(this, i)
                    updateStatus("Ready • capture on")
                } catch (t: Throwable) {
                    updateStatus("Capture start failed: ${t.message}")
                }
            } else {
                updateStatus("Ready • capture denied")
            }
        }

    private var screenCaptureRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ensureMicThenStart()

        val root = findViewById<ConstraintLayout>(R.id.root)
        val fab = findViewById<FloatingActionButton>(R.id.fabSphere)
        val pill = findViewById<MaterialCardView>(R.id.statusPill)
        val tvStatus = findViewById<MaterialTextView>(R.id.tvStatus)

        // circular outline
        fab.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        fab.clipToOutline = true
        fab.elevation = 18f

        // tap = narrate once
        fab.setOnClickListener {
            sendServiceAction(LiveS2SService.ACTION_NARRATE_ONCE)
            updateStatus("Describing…")
        }

        // long = toggle auto
        fab.setOnLongClickListener {
            sendServiceAction(LiveS2SService.ACTION_TOGGLE_AUTO)
            true
        }

        // double-tap = shush
        val doubleTapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    sendServiceAction(LiveS2SService.ACTION_SHUSH)
                    updateStatus("Running")
                    return true
                }
            }
        )

        // drag the orb
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var startX = 0f; var startY = 0f
        var moved = false

        fab.setOnTouchListener { v, e ->
            doubleTapDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = v.x; startY = v.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                        v.cancelLongPress()
                    }
                    val nx = startX + dx
                    val ny = startY + dy
                    val maxX = (root.width - v.width).toFloat().coerceAtLeast(0f)
                    val maxY = (root.height - v.height).toFloat().coerceAtLeast(0f)
                    v.x = nx.coerceIn(0f, maxX)
                    v.y = ny.coerceIn(0f, maxY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
                else -> false
            }
        }

        // long-press pill = end session
        pill.setOnLongClickListener {
            sendServiceAction(LiveS2SService.ACTION_END)
            updateStatus("Ending…")
            true
        }

        tvStatus?.text = "tap: describe  •  long-press: auto  •  double-tap: shush"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopService(Intent(this, ScreenCaptureService::class.java)) } catch (_: Throwable) {}
    }

    private fun ensureMicThenStart() {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        ensureNotifThenStart()
    }

    private fun ensureNotifThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startVoiceServiceAndScreenCapture()
    }

    private fun startVoiceServiceAndScreenCapture() {
        startVoiceService()
        if (!screenCaptureRequested) {
            screenCaptureRequested = true
            requestScreenCapture()
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, LiveS2SService::class.java).apply {
            action = LiveS2SService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, intent)
            updateStatus("Ready")
        } catch (t: Throwable) {
            updateStatus("Failed to start: ${t.message}")
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun sendServiceAction(action: String) {
        val i = Intent(this, LiveS2SService::class.java).setAction(action)
        try { ContextCompat.startForegroundService(this, i) } catch (_: Throwable) {}
    }

    private fun updateStatus(s: String) {
        findViewById<MaterialTextView>(R.id.tvStatus)?.text = s
    }
}
