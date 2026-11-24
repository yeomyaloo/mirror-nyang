package com.mirrornyang.app

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * MediaProjection 화면 -> VirtualDisplay -> MediaCodec(H.264) 인코더.
 * 인코딩된 프레임(Annex B)은 [onFrame]으로 넘긴다.
 */
class H264Encoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitrate: Int,
    private val onFrame: (data: ByteArray, isKey: Boolean, isConfig: Boolean) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastKeyRequest = 0L

    fun start() {
        val codec = createCodec()
        this.codec = codec
        inputSurface = codec.createInputSurface()
        codec.start()
        display = projection.createVirtualDisplay(
            "MirrorNyang-h264", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
        running = true
        thread = Thread({ drainLoop(codec) }, "h264-drain").also { it.start() }
        Log.i(TAG, "encoder started ${width}x$height @ ${bitrate / 1000}kbps")
    }

    private fun createCodec(): MediaCodec {
        // 먼저 Baseline 프로파일로 시도하고(브라우저 호환성 최고), 거부되면 기본값으로
        for (withProfile in listOf(true, false)) {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.configure(buildFormat(withProfile), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "configure failed (profile=$withProfile): ${e.message}")
                codec.release()
            }
        }
        throw IllegalStateException("이 기기의 H.264 인코더를 설정할 수 없어요")
    }

    private fun buildFormat(withProfile: Boolean) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // 화면이 안 바뀌어도 0.1초마다 프레임을 내보내서 브라우저 재생이 멈추지 않게
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (withProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    onFrame(data, isKey, isConfig)
                }
                codec.releaseOutputBuffer(index, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "drain stopped: ${e.message}")
        }
    }

    /** 새 수신기가 바로 화면을 볼 수 있도록 키프레임을 요청 (0.5초에 한 번까지) */
    fun requestKeyFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyRequest < 500) return
        lastKeyRequest = now
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            Log.w(TAG, "key frame request failed: ${e.message}")
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        display?.release()
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
        }
        codec?.release()
        inputSurface?.release()
        display = null
        codec = null
        inputSurface = null
    }

    companion object {
        private const val TAG = "MirrorNyang/H264"
    }
}
