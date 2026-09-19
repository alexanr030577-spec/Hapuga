package ru.hapuga.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

class HuntService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_DATA="data"
        const val ACTION_NEW_ORDER="ru.hapuga.NEW_ORDER"
        const val ACTION_ORDER_DETECTED_EXTERNAL="ru.hapuga.ORDER_DETECTED"
        const val ACTION_DIAGNOSTIC="ru.hapuga.DIAGNOSTIC"
        const val ACTION_STATE="ru.hapuga.STATE"; const val ACTION_QUERY_STATE="ru.hapuga.QUERY_STATE"; const val ACTION_STOP="ru.hapuga.STOP"
        const val EXTRA_MARKER_COUNT="markerCount"; const val EXTRA_PURPLE_COUNT="purpleCount"; const val EXTRA_RUNNING="running"; const val CHANNEL="hunt"
    }

    private var projection:MediaProjection?=null
    private var reader:ImageReader?=null
    private var tts:TextToSpeech?=null

    private data class Seen(var x:Int,var y:Int,var lastSeen:Long)
    private data class Anchor(val x:Int,val y:Int,val gray:Int)

    private val seen=mutableListOf<Seen>()
    private var baselineReady=false
    private var lastDiagnostic=0L

    // Screen-space shift of the map relative to the reference view.
    // This lets the drawn geographic zone move together with the map after Hunt starts.
    private var zoneShiftX=0
    private var zoneShiftY=0
    private var anchors:List<Anchor>?=null
    private var lastMotionCheck=0L

    private val handlerThread=HandlerThread("hapuga-capture").apply{start()}
    private val handler=Handler(handlerThread.looper)
    private val ui=Handler(Looper.getMainLooper())

    private var pointer:PointerView?=null
    private var pointerRemove:Runnable?=null

    private val queryReceiver=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){broadcastState()}
    }

    override fun onCreate(){
        super.onCreate()
        tts=TextToSpeech(this){if(it==TextToSpeech.SUCCESS)tts?.language=Locale("ru","RU")}
        val f=IntentFilter(ACTION_QUERY_STATE)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(queryReceiver,f,RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(queryReceiver,f)
    }

    override fun onBind(intent:Intent?)=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopHunt();return START_NOT_STICKY}
        createChannel()
        startForeground(
            1,
            NotificationCompat.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Хапуга")
                .setContentText("Охота включена — Строгино")
                .setOngoing(true)
                .build()
        )
        if(projection==null && intent!=null){
            val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(EXTRA_DATA,Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA)
            if(data!=null)startCapture(code,data)
        }
        broadcastState()
        return START_NOT_STICKY
    }

    private fun broadcastState(){
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_RUNNING,projection!=null))
    }

    private fun stopHunt(){
        reader?.setOnImageAvailableListener(null,null)
        reader?.close(); reader=null
        projection?.stop(); projection=null
        seen.clear(); baselineReady=false
        anchors=null; zoneShiftX=0; zoneShiftY=0
        hidePointer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastState()
        stopSelf()
    }

    private fun startCapture(code:Int,data:Intent){
        projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(code,data)
        projection?.registerCallback(object:MediaProjection.Callback(){
            override fun onStop(){projection=null;broadcastState();stopSelf()}
        },handler)

        val dm=resources.displayMetrics
        val w=dm.widthPixels
        val h=dm.heightPixels
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        projection?.createVirtualDisplay("Hapuga",w,h,dm.densityDpi,0,reader!!.surface,null,handler)

        reader?.setOnImageAvailableListener({r->
            val image=r.acquireLatestImage()?:return@setOnImageAvailableListener
            try{
                val p=image.planes[0]
                val pad=p.rowStride-p.pixelStride*image.width
                val bm=Bitmap.createBitmap(image.width+pad/p.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                bm.copyPixelsFromBuffer(p.buffer)
                scan(bm,image.width,image.height)
                bm.recycle()
            }finally{image.close()}
        },handler)
    }

    // Reference zone for the current Strogino working area.
    // Unlike v0.10, it is shifted on screen together with detected map panning.
    private fun inBaseZone(nx:Double,ny:Double):Boolean{
        val p=arrayOf(
            .18 to .10,.31 to .075,.48 to .08,.63 to .10,.74 to .14,.82 to .20,
            .87 to .30,.88 to .42,.88 to .54,.88 to .63,.85 to .69,.80 to .72,
            .73 to .735,.65 to .74,.58 to .73,.52 to .705,.45 to .69,.38 to .68,
            .30 to .65,.22 to .62,.15 to .57,.10 to .51,.075 to .43,.08 to .35,
            .10 to .27,.12 to .19
        )
        var inside=false
        var j=p.lastIndex
        for(i in p.indices){
            val xi=p[i].first; val yi=p[i].second
            val xj=p[j].first; val yj=p[j].second
            if((yi>ny)!=(yj>ny) && nx < (xj-xi)*(ny-yi)/(yj-yi)+xi)inside=!inside
            j=i
        }
        return inside
    }

    private fun inZone(x:Int,y:Int,w:Int,h:Int):Boolean{
        val nx=(x-zoneShiftX).toDouble()/w
        val ny=(y-zoneShiftY).toDouble()/h
        return inBaseZone(nx,ny)
    }

    private fun purple(c:Int):Boolean{
        val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
        return r>105 && b>120 && b>g+25 && r>g+20 && (maxOf(r,g,b)-minOf(r,g,b))>35
    }

    private fun light(c:Int):Boolean{
        val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
        return r>185 && g>185 && b>185 && abs(r-g)<45 && abs(g-b)<45
    }

    private fun gray(c:Int):Int=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100

    private fun buildAnchors(bm:Bitmap,w:Int,h:Int):List<Anchor>{
        val out=ArrayList<Anchor>()
        val x0=(w*.06).toInt(); val x1=(w*.78).toInt()
        val y0=(h*.13).toInt(); val y1=(h*.75).toInt()
        var y=y0
        while(y<y1){
            var x=x0
            while(x<x1){
                val c=bm.getPixel(x,y)
                val g=gray(c)
                // Avoid very flat white/black areas and purple order markers.
                if(!purple(c) && g in 55..238)out+=Anchor(x,y,g)
                x+=26
            }
            y+=26
        }
        return out
    }

    private fun updateMapMotion(bm:Bitmap,w:Int,h:Int,now:Long){
        if(now-lastMotionCheck<550)return
        lastMotionCheck=now
        val prev=anchors
        if(prev!=null && prev.size>80){
            var bestDx=0; var bestDy=0
            var bestScore=Double.MAX_VALUE

            var dy=-120
            while(dy<=120){
                var dx=-120
                while(dx<=120){
                    var sum=0L; var count=0
                    for(a in prev){
                        val xx=a.x+dx; val yy=a.y+dy
                        if(xx<2||yy<2||xx>=w-2||yy>=h-2)continue
                        val c=bm.getPixel(xx,yy)
                        if(purple(c))continue
                        sum+=abs(gray(c)-a.gray)
                        count++
                    }
                    if(count>80){
                        val score=sum.toDouble()/count
                        if(score<bestScore){bestScore=score;bestDx=dx;bestDy=dy}
                    }
                    dx+=12
                }
                dy+=12
            }

            // Low score means the same map background was found at a shifted location.
            // Reject scene changes (another WB screen, loading panel, etc).
            if(bestScore<19.0 && (abs(bestDx)>=12 || abs(bestDy)>=12)){
                zoneShiftX=(zoneShiftX+bestDx).coerceIn(-w,w)
                zoneShiftY=(zoneShiftY+bestDy).coerceIn(-h,h)
            }
        }
        anchors=buildAnchors(bm,w,h)
    }

    private fun scan(bm:Bitmap,w:Int,h:Int){
        val now=SystemClock.elapsedRealtime()
        updateMapMotion(bm,w,h,now)

        val cand=mutableListOf<Pair<Int,Int>>()
        for(y in 25 until h-25 step 9){
            for(x in 25 until w-25 step 9){
                if(y<h*.08 || y>h*.90 || !inZone(x,y,w,h))continue
                var pn=0; var ln=0
                for(dy in -21..21 step 7){
                    for(dx in -21..21 step 7){
                        val c=bm.getPixel(x+dx,y+dy)
                        if(purple(c))pn++
                        if(abs(dx)<=14 && abs(dy)<=14 && light(c))ln++
                    }
                }
                if(pn>=8 && ln>=1)cand+=x to y
            }
        }

        val clusters=mutableListOf<Pair<Int,Int>>()
        for(p in cand)if(clusters.none{abs(it.first-p.first)<70 && abs(it.second-p.second)<70})clusters+=p

        if(now-lastDiagnostic>1000){
            sendBroadcast(
                Intent(ACTION_DIAGNOSTIC).setPackage(packageName)
                    .putExtra(EXTRA_MARKER_COUNT,clusters.size)
                    .putExtra(EXTRA_PURPLE_COUNT,cand.size)
            )
            lastDiagnostic=now
        }

        seen.removeAll{now-it.lastSeen>180000}
        val matched=mutableSetOf<Seen>()
        val newPoints=mutableListOf<Pair<Int,Int>>()

        for(p in clusters){
            val oldSeen=seen
                .filter{it !in matched && abs(it.x-p.first)<70 && abs(it.y-p.second)<70}
                .minByOrNull{q->abs(q.x-p.first)+abs(q.y-p.second)}

            if(oldSeen!=null){
                oldSeen.x=p.first; oldSeen.y=p.second; oldSeen.lastSeen=now
                matched+=oldSeen
            }else{
                seen+=Seen(p.first,p.second,now)
                if(baselineReady)newPoints+=p
            }
        }

        if(newPoints.isNotEmpty()){
            val p=newPoints.first()
            showPointer(p.first.toFloat()/w,p.second.toFloat()/h)
            orderAlert()
            openCourier()
            sendBroadcast(Intent(ACTION_NEW_ORDER).setPackage(packageName))
            sendBroadcast(Intent(ACTION_ORDER_DETECTED_EXTERNAL))
        }
        baselineReady=true
    }

    private fun openCourier(){
        val launch=packageManager.getLaunchIntentForPackage("com.wildberries.courier")
        if(launch!=null){
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try{startActivity(launch)}catch(_:Exception){}
        }
    }

    private fun orderAlert(){
        val vib=getSystemService(Vibrator::class.java)
        if(Build.VERSION.SDK_INT>=26)vib.vibrate(VibrationEffect.createOneShot(700,VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vib.vibrate(700)
        tts?.speak("Лёш, заказ!",TextToSpeech.QUEUE_FLUSH,null,"order")
    }

    private fun showPointer(nx:Float,ny:Float){
        if(!Settings.canDrawOverlays(this))return
        ui.post{
            val wm=getSystemService(WINDOW_SERVICE) as WindowManager
            var v=pointer
            if(v==null){
                v=PointerView(this)
                val lp=WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                try{wm.addView(v,lp);pointer=v}catch(_:Exception){return@post}
            }
            v.setPoint(nx,ny)
            pointerRemove?.let(ui::removeCallbacks)
            pointerRemove=Runnable{hidePointer()}
            ui.postDelayed(pointerRemove!!,900)
        }
    }

    private fun hidePointer(){
        ui.post{
            val v=pointer?:return@post
            try{(getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)}catch(_:Exception){}
            pointer=null
        }
    }

    private class PointerView(ctx:Context):View(ctx){
        private var nx=.5f; private var ny=.5f
        private val black=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=14f;color=Color.BLACK}
        private val yellow=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=8f;color=Color.YELLOW}
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL;color=Color.YELLOW}

        fun setPoint(x:Float,y:Float){nx=x;ny=y;invalidate()}

        override fun onDraw(c:Canvas){
            super.onDraw(c)
            val x=width*nx; val y=height*ny
            c.drawCircle(x,y,52f,black)
            c.drawCircle(x,y,52f,yellow)

            val tipY=y-62f
            val topY=y-145f
            c.drawLine(x,topY,x,tipY-18f,black)
            c.drawLine(x,topY,x,tipY-18f,yellow)

            val path=Path().apply{
                moveTo(x,tipY)
                lineTo(x-27f,tipY-34f)
                lineTo(x+27f,tipY-34f)
                close()
            }
            c.drawPath(path,Paint(fill).apply{color=Color.BLACK})
            val inner=Path().apply{
                moveTo(x,tipY-5f)
                lineTo(x-18f,tipY-30f)
                lineTo(x+18f,tipY-30f)
                close()
            }
            c.drawPath(inner,fill)
        }
    }

    private fun createChannel(){
        if(Build.VERSION.SDK_INT>=26)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL,"Охота",NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy(){
        try{unregisterReceiver(queryReceiver)}catch(_:Exception){}
        reader?.close()
        projection?.stop()
        tts?.shutdown()
        hidePointer()
        handlerThread.quitSafely()
        super.onDestroy()
    }
}
