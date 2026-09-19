package ru.hapuga.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class HuntService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_NEW_ORDER = "ru.hapuga.NEW_ORDER"
        const val CHANNEL = "hunt"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var previous = emptyList<Pair<Int,Int>>()
    private val handlerThread = HandlerThread("hapuga-capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(1, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Хапуга")
            .setContentText("Охота включена — Строгино")
            .setOngoing(true).build())

        if (projection == null && intent != null) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_DATA)
            if (data != null) startCapture(code, data)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val pm = getSystemService(MediaProjectionManager::class.java)
        projection = pm.getMediaProjection(code, data)
        projection?.registerCallback(object: MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        projection?.createVirtualDisplay("Hapuga", w, h, dm.densityDpi,
            0, reader!!.surface, null, handler)
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * image.width
                val bmp = Bitmap.createBitmap(image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                scan(bmp, image.width, image.height)
                bmp.recycle()
            } finally { image.close() }
        }, handler)
    }

    private fun inStroginoZone(nx: Double, ny: Double): Boolean {
        // v0.2 fixed polygon calibrated from Lesha's WB Courier portrait map view.
        // Normalized coordinates keep it stable across equivalent screen resolutions.
        val polygon = arrayOf(
            0.00 to 0.61,
            0.13 to 0.57,
            0.26 to 0.57,
            0.38 to 0.61,
            0.50 to 0.68,
            0.64 to 0.72,
            0.78 to 0.75,
            0.93 to 0.77,
            1.00 to 0.78,
            1.00 to 0.91,
            0.86 to 0.91,
            0.72 to 0.88,
            0.58 to 0.84,
            0.44 to 0.80,
            0.30 to 0.77,
            0.16 to 0.74,
            0.04 to 0.70
        )
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val xi=polygon[i].first; val yi=polygon[i].second
            val xj=polygon[j].first; val yj=polygon[j].second
            if (((yi > ny) != (yj > ny)) &&
                (nx < (xj-xi) * (ny-yi) / (yj-yi) + xi)) inside = !inside
            j=i
        }
        return inside
    }

    private fun scan(b: Bitmap, w: Int, h: Int) {
        val hits = mutableListOf<Pair<Int,Int>>()
        for (y in 0 until h step 7) for (x in 0 until w step 7) {
            if (!inStroginoZone(x.toDouble()/w, y.toDouble()/h)) continue
            val c=b.getPixel(x,y)
            val r=android.graphics.Color.red(c)
            val g=android.graphics.Color.green(c)
            val bl=android.graphics.Color.blue(c)
            // WB marker purple: high red+blue, clearly lower green.
            if (r > 130 && bl > 145 && r > g * 1.28 && bl > g * 1.28) hits += x to y
        }

        val clusters=mutableListOf<Pair<Int,Int>>()
        for (p in hits) if (clusters.none { abs(it.first-p.first)<58 && abs(it.second-p.second)<58 }) clusters += p

        // First frame establishes baseline. Thereafter alert only for a newly appeared marker.
        val isNew = previous.isNotEmpty() && clusters.any { p ->
            previous.none { q -> abs(q.first-p.first)<72 && abs(q.second-p.second)<72 }
        }
        if (isNew) sendBroadcast(Intent(ACTION_NEW_ORDER).setPackage(packageName))
        previous=clusters
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Охота", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        reader?.close(); projection?.stop(); handlerThread.quitSafely()
        super.onDestroy()
    }
}
