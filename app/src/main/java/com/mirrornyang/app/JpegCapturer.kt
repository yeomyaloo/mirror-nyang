package com.mirrornyang.app

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * 호환 모드: 화면을 JPEG 사진으로 연속 전송한다.
 * H.264(MSE) 재생이 안 되는 브라우저(일부 구형 TV)용. 화질·프레임은 낮지만 거의 어디서나 동작한다.
 */
class JpegCapturer(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val hasClients: () -> Boolean,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val thread = HandlerThread("jpeg-capture")
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var bitmap: Bitmap? = null
    private var lastFrameAt = 0L
    private val out = ByteArrayOutputStream(256 * 1024)

    fun start() {
        thread.start()
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        this.reader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (!hasClients() || now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = image.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                var bmp = bitmap
                if (bmp == null || bmp.width != rowPixels || bmp.height != height) {
                    bmp = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
                    bitmap = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer)
                // 줄 끝 여백(padding)이 있으면 잘라낸다
                val frame = if (rowPixels != width) Bitmap.createBitmap(bmp, 0, 0, width, height) else bmp
                out.reset()
                frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                if (frame !== bmp) frame.recycle()
                onFrame(out.toByteArray())
            } finally {
                image.close()
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "MirrorNyang-jpeg", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    fun stop() {
        display?.release()
        reader?.close()
        thread.quitSafely()
        bitmap?.recycle()
        display = null
        reader = null
        bitmap = null
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 80L // 약 12fps
        private const val QUALITY = 60
    }
}
