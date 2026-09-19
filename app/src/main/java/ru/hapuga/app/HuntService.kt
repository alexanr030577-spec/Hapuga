package ru.hapuga.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class HuntService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_DATA="data"
        const val ACTION_NEW_ORDER="ru.hapuga.NEW_ORDER"; const val ACTION_DIAGNOSTIC="ru.hapuga.DIAGNOSTIC"
        const val ACTION_STATE="ru.hapuga.STATE"; const val ACTION_QUERY_STATE="ru.hapuga.QUERY_STATE"; const val ACTION_STOP="ru.hapuga.STOP"
        const val EXTRA_MARKER_COUNT="markerCount"; const val EXTRA_PURPLE_COUNT="purpleCount"; const val EXTRA_RUNNING="running"; const val CHANNEL="hunt"
    }
    private var projection:MediaProjection?=null; private var reader:ImageReader?=null; private var tts:TextToSpeech?=null
    private data class Seen(val x:Int,val y:Int,var lastSeen:Long)
    private val seen=mutableListOf<Seen>(); private var baselineReady=false; private var lastDiagnostic=0L
    private val handlerThread=HandlerThread("hapuga-capture").apply{start()}; private val handler=Handler(handlerThread.looper)
    private val queryReceiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){broadcastState()}}

    override fun onCreate(){super.onCreate();tts=TextToSpeech(this){if(it==TextToSpeech.SUCCESS)tts?.language=Locale("ru","RU")};val f=IntentFilter(ACTION_QUERY_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(queryReceiver,f,RECEIVER_NOT_EXPORTED)else @Suppress("DEPRECATION") registerReceiver(queryReceiver,f)}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopHunt();return START_NOT_STICKY}
        createChannel();startForeground(1,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Хапуга").setContentText("Охота включена — Строгино").setOngoing(true).build())
        if(projection==null && intent!=null){val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);@Suppress("DEPRECATION") val data=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(EXTRA_DATA,Intent::class.java)else intent.getParcelableExtra(EXTRA_DATA);if(data!=null)startCapture(code,data)}
        broadcastState();return START_NOT_STICKY
    }
    private fun broadcastState(){sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_RUNNING,projection!=null))}
    private fun stopHunt(){reader?.setOnImageAvailableListener(null,null);reader?.close();reader=null;projection?.stop();projection=null;seen.clear();baselineReady=false;stopForeground(STOP_FOREGROUND_REMOVE);broadcastState();stopSelf()}
    private fun startCapture(code:Int,data:Intent){projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(code,data);projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){projection=null;broadcastState();stopSelf()}},handler);val dm=resources.displayMetrics;val w=dm.widthPixels;val h=dm.heightPixels;reader=ImageReader.newInstance(w,h,android.graphics.PixelFormat.RGBA_8888,2);projection?.createVirtualDisplay("Hapuga",w,h,dm.densityDpi,0,reader!!.surface,null,handler);reader?.setOnImageAvailableListener({r->val image=r.acquireLatestImage()?:return@setOnImageAvailableListener;try{val p=image.planes[0];val pad=p.rowStride-p.pixelStride*image.width;val bm=Bitmap.createBitmap(image.width+pad/p.pixelStride,image.height,Bitmap.Config.ARGB_8888);bm.copyPixelsFromBuffer(p.buffer);scan(bm,image.width,image.height);bm.recycle()}finally{image.close()}},handler)}
    private fun inZone(nx:Double,ny:Double):Boolean{val p=arrayOf(0.00 to .61,.13 to .57,.26 to .57,.38 to .61,.50 to .68,.64 to .72,.78 to .75,.93 to .77,1.00 to .78,1.00 to .91,.86 to .91,.72 to .88,.58 to .84,.44 to .80,.30 to .77,.16 to .74,.04 to .70);var inside=false;var j=p.lastIndex;for(i in p.indices){val xi=p[i].first;val yi=p[i].second;val xj=p[j].first;val yj=p[j].second;if((yi>ny)!=(yj>ny)&&nx<(xj-xi)*(ny-yi)/(yj-yi)+xi)inside=!inside;j=i};return inside}
    private fun purple(c:Int):Boolean{val r=android.graphics.Color.red(c);val g=android.graphics.Color.green(c);val b=android.graphics.Color.blue(c);return r>105&&b>120&&b>g+25&&r>g+20&&(maxOf(r,g,b)-minOf(r,g,b))>35}
    private fun light(c:Int):Boolean{val r=android.graphics.Color.red(c);val g=android.graphics.Color.green(c);val b=android.graphics.Color.blue(c);return r>185&&g>185&&b>185&&abs(r-g)<45&&abs(g-b)<45}
    private fun scan(bm:Bitmap,w:Int,h:Int){val cand=mutableListOf<Pair<Int,Int>>();for(y in 25 until h-25 step 9)for(x in 25 until w-25 step 9){if(!inZone(x.toDouble()/w,y.toDouble()/h))continue;var pn=0;var ln=0;for(dy in -21..21 step 7)for(dx in -21..21 step 7){val c=bm.getPixel(x+dx,y+dy);if(purple(c))pn++;if(abs(dx)<=14&&abs(dy)<=14&&light(c))ln++};if(pn>=8&&ln>=1)cand+=x to y};val clusters=mutableListOf<Pair<Int,Int>>();for(p in cand)if(clusters.none{abs(it.first-p.first)<70&&abs(it.second-p.second)<70})clusters+=p;val now=SystemClock.elapsedRealtime();if(now-lastDiagnostic>1000){sendBroadcast(Intent(ACTION_DIAGNOSTIC).setPackage(packageName).putExtra(EXTRA_MARKER_COUNT,clusters.size).putExtra(EXTRA_PURPLE_COUNT,cand.size));lastDiagnostic=now};val t=SystemClock.elapsedRealtime(); seen.removeAll{t-it.lastSeen>180000}; var isNew=false; for(p in clusters){val oldSeen=seen.minByOrNull{q->abs(q.x-p.first)+abs(q.y-p.second)}?.takeIf{q->abs(q.x-p.first)<120&&abs(q.y-p.second)<120};if(oldSeen!=null)oldSeen.lastSeen=t else {seen+=Seen(p.first,p.second,t);if(baselineReady)isNew=true}};if(isNew){orderAlert();sendBroadcast(Intent(ACTION_NEW_ORDER).setPackage(packageName))};baselineReady=true}
    private fun orderAlert(){val vib=getSystemService(Vibrator::class.java);if(Build.VERSION.SDK_INT>=26)vib.vibrate(VibrationEffect.createOneShot(700,VibrationEffect.DEFAULT_AMPLITUDE))else @Suppress("DEPRECATION") vib.vibrate(700);tts?.speak("Лёш, заказ!",TextToSpeech.QUEUE_FLUSH,null,"order")}
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Охота",NotificationManager.IMPORTANCE_LOW))}
    override fun onDestroy(){try{unregisterReceiver(queryReceiver)}catch(_:Exception){};reader?.close();projection?.stop();tts?.shutdown();handlerThread.quitSafely();super.onDestroy()}
}
