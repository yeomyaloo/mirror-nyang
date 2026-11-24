package com.mirrornyang.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 미러링 본체. 포그라운드 서비스로 돌면서
 * 화면 캡처(MediaProjection) + 인코딩 + 스트리밍 서버를 관리한다.
 */
class MirrorService : Service(), StreamServer.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var server: StreamServer? = null
    private var h264: H264Encoder? = null
    private var jpeg: JpegCapturer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var screenW = 0
    private var screenH = 0
    private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // 이미 실행 중

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 10+ 에서는 getMediaProjection 전에 반드시 포그라운드 상태여야 한다
        startInForeground()

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("화면 공유 권한을 받지 못했어요")
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped by system")
                    stopSelf()
                }
            }, mainHandler)
            projection = p

            readScreenSize()
            val s = StreamServer(this, this)
            s.start(8080)
            server = s
            acquireLocks()

            urls = NetUtil.localIpv4Addresses().map { "http://$it:${s.port}" }
            running = true
            updateNotification()
            notifyStatus()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            Toast.makeText(this, "시작하지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readScreenSize() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        dpi = metrics.densityDpi
    }

    /** 긴 변을 [maxSide]에 맞추고 16의 배수로 맞춘 크기 (인코더 호환용) */
    private fun scaledSize(maxSide: Int): Pair<Int, Int> {
        val scale = min(1.0, maxSide.toDouble() / max(screenW, screenH))
        fun align(v: Double) = max(16, (v / 16).roundToInt() * 16)
        return align(screenW * scale) to align(screenH * scale)
    }

    // ------------------------------------------------ StreamServer.Listener

    override fun onClientConnected(kind: StreamServer.Kind) {
        synchronized(this) {
            val p = projection ?: return
            when (kind) {
                StreamServer.Kind.H264 -> {
                    val enc = h264
                    if (enc == null) {
                        val (w, h) = scaledSize(1280)
                        val newEnc = H264Encoder(p, w, h, dpi, 4_000_000) { data, isKey, isConfig ->
                            if (server?.broadcastH264(data, isKey, isConfig) == true) h264?.requestKeyFrame()
                        }
                        h264 = newEnc
                        try {
                            newEnc.start()
                        } catch (e: Exception) {
                            Log.e(TAG, "h264 start failed", e)
                            h264 = null
                        }
                    } else {
                        enc.requestKeyFrame()
                    }
                }
                StreamServer.Kind.JPEG -> {
                    if (jpeg == null) {
                        val (w, h) = scaledSize(960)
                        val cap = JpegCapturer(p, w, h, dpi,
                            hasClients = { (server?.clientCount(StreamServer.Kind.JPEG) ?: 0) > 0 },
                            onFrame = { server?.broadcastJpeg(it) })
                        jpeg = cap
                        try {
                            cap.start()
                        } catch (e: Exception) {
                            Log.e(TAG, "jpeg start failed", e)
                            jpeg = null
                        }
                    }
                }
            }
        }
    }

    override fun onClientsChanged() {
        mainHandler.post {
            updateNotification()
            notifyStatus()
        }
    }

    // ------------------------------------------------------------ 알림

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!running) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val viewers = server?.let { it.clientCount(StreamServer.Kind.H264) + it.clientCount(StreamServer.Kind.JPEG) } ?: 0
        val text = (urls.firstOrNull() ?: "준비 중…") + " · 보는 기기 ${viewers}대"

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        @Suppress("DEPRECATION")
        return builder
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    // ------------------------------------------------------- 잠금/정리

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "MirrorNyang:wifi").apply { acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MirrorNyang:cpu").apply { acquire() }
    }

    override fun onDestroy() {
        running = false
        urls = emptyList()
        synchronized(this) {
            h264?.stop(); h264 = null
            jpeg?.stop(); jpeg = null
        }
        server?.stop(); server = null
        projection?.stop(); projection = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        notifyStatus()
        super.onDestroy()
    }

    private fun notifyStatus() {
        mainHandler.post { statusListener?.invoke() }
    }

    companion object {
        private const val TAG = "MirrorNyang"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.mirrornyang.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile
        var running = false
            private set

        @Volatile
        var urls: List<String> = emptyList()
            private set

        /** 화면(MainActivity)이 상태 변화를 받기 위한 콜백 */
        var statusListener: (() -> Unit)? = null
    }
}
