package com.mirrornyang.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var url: TextView
    private lateinit var qr: ImageView
    private lateinit var toggle: Button
    private var shownUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        url = findViewById(R.id.url)
        qr = findViewById(R.id.qr)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (MirrorService.running) {
                startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MirrorService.statusListener = { refresh() }
        refresh()
    }

    override fun onPause() {
        MirrorService.statusListener = null
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.denied, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MirrorService.EXTRA_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "준비 중이에요…"
    }

    private fun refresh() {
        if (!MirrorService.running) {
            status.setText(R.string.status_idle)
            toggle.setText(R.string.start)
            url.visibility = View.GONE
            qr.visibility = View.GONE
            shownUrl = null
            return
        }
        toggle.setText(R.string.stop)
        val urls = MirrorService.urls
        if (urls.isEmpty()) {
            status.setText(R.string.no_wifi)
            url.visibility = View.GONE
            qr.visibility = View.GONE
            return
        }
        status.setText(R.string.status_running)
        url.text = urls.joinToString("\n")
        url.visibility = View.VISIBLE
        val first = urls.first()
        if (first != shownUrl) {
            qr.setImageBitmap(makeQr(first))
            shownUrl = first
        }
        qr.visibility = View.VISIBLE
    }

    private fun makeQr(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.rgb(0x5B, 0x3A, 0x29) else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val REQ_CAPTURE = 1001
    }
}
