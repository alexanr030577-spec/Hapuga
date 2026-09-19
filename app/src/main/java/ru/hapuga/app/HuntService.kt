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
        const val ACTION_DIAGNOSTIC = "ru.hapuga.DIAGNOSTIC"
        const val EXTRA_MARKER_COUNT = "markerCount"
        const val CHANNEL = "hunt"
    }
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var previous = emptyList<Pair<Int,Int>>()
    private var lastDiagnostic = 0L
    private val handlerThread = HandlerThread("hapuga-capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(1, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Хапуга").setContentText("Охота включена — Строгино")
            .setOngoing(true).build())
        if (projection == null && intent != null) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                       else intent.getParcelableExtra(EXTRA_DATA)
            if (data != null) startCapture(code, data)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection?.registerCallback(object: MediaProjection.Callback(){ override fun onStop(){ stopSelf() }}, handler)
        val dm=resources.displayMetrics; val w=dm.widthPixels; val h=dm.heightPixels
        reader=ImageReader.newInstance(w,h,android.graphics.PixelFormat.RGBA_8888,2)
        projection?.createVirtualDisplay("Hapuga",w,h,dm.densityDpi,0,reader!!.surface,null,handler)
        reader?.setOnImageAvailableListener({ r ->
            val image=r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val p=image.planes[0]; val pad=p.rowStride-p.pixelStride*image.width
                val bmp=Bitmap.createBitmap(image.width+pad/p.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(p.buffer); scan(bmp,image.width,image.height); bmp.recycle()
            } finally { image.close() }
        },handler)
    }

    private fun inZone(nx:Double,ny:Double):Boolean {
        val p=arrayOf(0.00 to .61,.13 to .57,.26 to .57,.38 to .61,.50 to .68,.64 to .72,.78 to .75,.93 to .77,1.00 to .78,1.00 to .91,.86 to .91,.72 to .88,.58 to .84,.44 to .80,.30 to .77,.16 to .74,.04 to .70)
        var inside=false; var j=p.lastIndex
        for(i in p.indices){val xi=p[i].first;val yi=p[i].second;val xj=p[j].first;val yj=p[j].second
            if((yi>ny)!=(yj>ny) && nx<(xj-xi)*(ny-yi)/(yj-yi)+xi) inside=!inside
            j=i}
        return inside
    }

    private fun purple(c:Int):Boolean {
        val r=android.graphics.Color.red(c); val g=android.graphics.Color.green(c); val b=android.graphics.Color.blue(c)
        return r in 145..235 && b in 180..255 && g < 125 && b > g+65 && r > g+55
    }
    private fun light(c:Int):Boolean {
        val r=android.graphics.Color.red(c); val g=android.graphics.Color.green(c); val b=android.graphics.Color.blue(c)
        return r>205 && g>205 && b>205
    }

    private fun scan(bm:Bitmap,w:Int,h:Int){
        val candidates=mutableListOf<Pair<Int,Int>>()
        // Find dense purple marker bodies, then require a light/white digit near the center.
        for(y in 25 until h-25 step 9) for(x in 25 until w-25 step 9){
            if(!inZone(x.toDouble()/w,y.toDouble()/h)) continue
            var purpleN=0; var lightN=0
            for(dy in -21..21 step 7) for(dx in -21..21 step 7){
                val c=bm.getPixel(x+dx,y+dy)
                if(purple(c)) purpleN++
                if(abs(dx)<=14 && abs(dy)<=14 && light(c)) lightN++
            }
            if(purpleN>=16 && lightN>=1) candidates += x to y
        }
        val clusters=mutableListOf<Pair<Int,Int>>()
        for(p in candidates) if(clusters.none{abs(it.first-p.first)<70 && abs(it.second-p.second)<70}) clusters+=p

        val now=SystemClock.elapsedRealtime()
        if(now-lastDiagnostic>1000){
            sendBroadcast(Intent(ACTION_DIAGNOSTIC).setPackage(packageName).putExtra(EXTRA_MARKER_COUNT,clusters.size))
            lastDiagnostic=now
        }
        val isNew=previous.isNotEmpty() && clusters.any{p->previous.none{q->abs(q.first-p.first)<78&&abs(q.second-p.second)<78}}
        if(isNew) sendBroadcast(Intent(ACTION_NEW_ORDER).setPackage(packageName))
        previous=clusters
    }

    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL,"Охота",NotificationManager.IMPORTANCE_LOW))}
    override fun onDestroy(){reader?.close();projection?.stop();handlerThread.quitSafely();super.onDestroy()}
}
