package com.tradewind.visionagent.perception_exec

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.max

class ScreenCapture(private val app: Application) {

    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(resultCode: Int, data: Intent, width: Int, height: Int, densityDpi: Int) {
        stop()
        val mpm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(resultCode, data)

        // RGBA_8888 is fine; depth buffer 2 is enough for latest frame semantics
        reader = if (Build.VERSION.SDK_INT >= 29)
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888/*RGBA_8888*/, 2)
        else
            ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        vd = proj!!.createVirtualDisplay(
            "vision_capture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )

        scope.launch {
            while (isActive) {
                reader?.acquireLatestImage()?.use { img ->
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * img.width

                    val bmp = Bitmap.createBitmap(
                        img.width + rowPadding / pixelStride,
                        img.height,
                        Bitmap.Config.ARGB_8888
                    ).apply { copyPixelsFromBuffer(buf) }

                    val crop = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                    val scaled = downscale(crop, 1024) // longest side ≈ 1024
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    PerceptionStore.updateScreenshot(baos.toByteArray(), scaled.width, scaled.height)
                }
                // Idle @ ~2 fps; PerceptionStore.fpsHint can tell your loop to go faster later if you want
                delay(500)
            }
        }
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val scale = max(src.width, src.height).toFloat() / maxSide
        if (scale <= 1f) return src
        val w = (src.width / scale).toInt()
        val h = (src.height / scale).toInt()
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        reader?.close(); reader = null
        vd?.release(); vd = null
        proj?.stop(); proj = null
    }
}
