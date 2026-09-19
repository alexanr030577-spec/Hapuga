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
            .setContentText("Охота включена")
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

    private fun scan(b: Bitmap, w: Int, h: Int) {
        // v0.1: approximate central map ROI. Calibration comes next.
        val x0 = (w * .08).toInt(); val x1 = (w * .92).toInt()
        val y0 = (h * .16).toInt(); val y1 = (h * .78).toInt()
        val hits = mutableListOf<Pair<Int,Int>>()
        for (y in y0 until y1 step 8) for (x in x0 until x1 step 8) {
            val c=b.getPixel(x,y)
            val r=android.graphics.Color.red(c); val g=android.graphics.Color.green(c); val bl=android.graphics.Color.blue(c)
            if (r > 115 && bl > 120 && r > g * 1.25 && bl > g * 1.25) hits += x to y
        }
        val clusters=mutableListOf<Pair<Int,Int>>()
        for (p in hits) if (clusters.none { abs(it.first-p.first)<55 && abs(it.second-p.second)<55 }) clusters += p
        val isNew = previous.isNotEmpty() && clusters.any { p -> previous.none { q -> abs(q.first-p.first)<70 && abs(q.second-p.second)<70 } }
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
