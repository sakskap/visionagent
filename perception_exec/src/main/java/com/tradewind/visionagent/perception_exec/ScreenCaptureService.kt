package com.tradewind.visionagent.perception_exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "capture.START"
        const val ACTION_STOP  = "capture.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CH_ID = "capture"
        private const val CH_NAME = "Screen Capture"
        private const val NOTIF_ID = 2001
    }

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    // A14+ requires a registered callback before starting capture.
    private val projCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Projection got revoked (user stopped share / system change) — clean up fast.
            bgHandler?.post {
                vDisplay?.release(); vDisplay = null
                imageReader?.close(); imageReader = null
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Don’t call startForeground() here for mediaProjection.
        bgThread = HandlerThread("CaptureBG").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCaptureWithFgs(intent)
            ACTION_STOP  -> stopSelf()
        }
        return START_STICKY
    }

    private fun startCaptureWithFgs(intent: Intent) {
        // Guard against double-starts
        if (projection != null && vDisplay != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return

        // 1) Promote to FGS with the mediaProjection type (we already have consent extras).
        val notif = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Screen capture active")
            .setContentText("VisionAgent is capturing the screen")
            .setOngoing(true)
            .build()
        startAsFgs(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        // 2) Obtain projection token.
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, resultData) ?: return

        // 3) A14+: register callback BEFORE starting capture.
        projection!!.registerCallback(projCallback, bgHandler)

        // 4) Create VirtualDisplay / ImageReader.
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val dpi = dm.densityDpi

        imageReader = ImageReader.newInstance(
            width, height, android.graphics.PixelFormat.RGBA_8888, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                // TODO: hand off frame to your pipeline
                img.close()
            }, bgHandler)
        }

        vDisplay = projection!!.createVirtualDisplay(
            "VisionAgentVD",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            imageReader?.surface, null, bgHandler
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try { projection?.unregisterCallback(projCallback) } catch (_: Throwable) {}
        vDisplay?.release(); vDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        bgThread?.quitSafely(); bgThread = null; bgHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun startAsFgs(id: Int, notif: Notification, typeMask: Int) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(id, notif, typeMask)
        else startForeground(id, notif)
    }
}
