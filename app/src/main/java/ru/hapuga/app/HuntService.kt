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
    private data class Seen(var x:Int,var y:Int,var lastSeen:Long,var strength:Int=0)
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
    private fun inZone(nx:Double,ny:Double):Boolean{val p=arrayOf(.14 to .11,.23 to .08,.36 to .09,.48 to .12,.59 to .12,.70 to .16,.78 to .23,.82 to .33,.82 to .43,.82 to .52,.78 to .60,.70 to .66,.61 to .70,.49 to .70,.38 to .72,.28 to .71,.24 to .66,.16 to .64,.11 to .58,.09 to .48,.11 to .38,.12 to .28,.14 to .19);var inside=false;var j=p.lastIndex;for(i in p.indices){val xi=p[i].first;val yi=p[i].second;val xj=p[j].first;val yj=p[j].second;if((yi>ny)!=(yj>ny)&&nx<(xj-xi)*(ny-yi)/(yj-yi)+xi)inside=!inside;j=i};return inside}
    private fun purple(c:Int):Boolean{val r=android.graphics.Color.red(c);val g=android.graphics.Color.green(c);val b=android.graphics.Color.blue(c);return r>105&&b>120&&b>g+25&&r>g+20&&(maxOf(r,g,b)-minOf(r,g,b))>35}
    private fun light(c:Int):Boolean{val r=android.graphics.Color.red(c);val g=android.graphics.Color.green(c);val b=android.graphics.Color.blue(c);return r>185&&g>185&&b>185&&abs(r-g)<45&&abs(g-b)<45}
    private fun scan(bm:Bitmap,w:Int,h:Int){val cand=mutableListOf<Pair<Int,Int>>();for(y in 25 until h-25 step 9)for(x in 25 until w-25 step 9){if(y<h*0.08||y>h*0.90||!inZone(x.toDouble()/w,y.toDouble()/h))continue;var pn=0;var ln=0;for(dy in -21..21 step 7)for(dx in -21..21 step 7){val c=bm.getPixel(x+dx,y+dy);if(purple(c))pn++;if(abs(dx)<=14&&abs(dy)<=14&&light(c))ln++};if(pn>=8&&ln>=1)cand+=x to y};val clusters=mutableListOf<Pair<Int,Int>>();for(p in cand)if(clusters.none{abs(it.first-p.first)<70&&abs(it.second-p.second)<70})clusters+=p;val now=SystemClock.elapsedRealtime();if(now-lastDiagnostic>1000){sendBroadcast(Intent(ACTION_DIAGNOSTIC).setPackage(packageName).putExtra(EXTRA_MARKER_COUNT,clusters.size).putExtra(EXTRA_PURPLE_COUNT,cand.size));lastDiagnostic=now};val t=SystemClock.elapsedRealtime(); seen.removeAll{t-it.lastSeen>180000}; var isNew=false; val matched=mutableSetOf<Seen>();for(p in clusters){val oldSeen=seen.filter{it !in matched&&abs(it.x-p.first)<70&&abs(it.y-p.second)<70}.minByOrNull{q->abs(q.x-p.first)+abs(q.y-p.second)};if(oldSeen!=null){oldSeen.lastSeen=t;matched+=oldSeen}else{seen+=Seen(p.first,p.second,t,0);if(baselineReady)isNew=true}};if(isNew){orderAlert();openCourier();sendBroadcast(Intent(ACTION_NEW_ORDER).setPackage(packageName))};baselineReady=true}
    private fun openCourier(){
        val launch=packageManager.getLaunchIntentForPackage("com.wildberries.courier")
        if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP);try{startActivity(launch)}catch(_:Exception){}}
    }
    private fun orderAlert(){val vib=getSystemService(Vibrator::class.java);if(Build.VERSION.SDK_INT>=26)vib.vibrate(VibrationEffect.createOneShot(700,VibrationEffect.DEFAULT_AMPLITUDE))else @Suppress("DEPRECATION") vib.vibrate(700);tts?.speak("Лёш, заказ!",TextToSpeech.QUEUE_FLUSH,null,"order")}
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Охота",NotificationManager.IMPORTANCE_LOW))}
    override fun onDestroy(){try{unregisterReceiver(queryReceiver)}catch(_:Exception){};reader?.close();projection?.stop();tts?.shutdown();handlerThread.quitSafely();super.onDestroy()}
}
