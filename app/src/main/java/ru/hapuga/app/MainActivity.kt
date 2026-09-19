package ru.hapuga.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.os.*
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var status: TextView
    private lateinit var huntButton: Button
    private var tts: TextToSpeech? = null
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HuntService.ACTION_STATE -> setRunning(intent.getBooleanExtra(HuntService.EXTRA_RUNNING, false))
                HuntService.ACTION_NEW_ORDER -> alert()
                HuntService.ACTION_DIAGNOSTIC -> {
                    val n=intent.getIntExtra(HuntService.EXTRA_MARKER_COUNT,0)
                    val p=intent.getIntExtra(HuntService.EXTRA_PURPLE_COUNT,0)
                    running=true; status.text="Охота • фиолетовых точек: $p • меток: $n"; huntButton.text="СТОП"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        projectionManager=getSystemService(MediaProjectionManager::class.java)
        status=findViewById(R.id.status); huntButton=findViewById(R.id.huntButton)
        tts=TextToSpeech(this,this)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
        huntButton.setOnClickListener {
            if(running) {
                startService(Intent(this,HuntService::class.java).setAction(HuntService.ACTION_STOP))
                setRunning(false)
            } else {
                if(!Settings.canDrawOverlays(this)){
                    Toast.makeText(this,"Разреши Хапуге показывать стрелку поверх WB, потом нажми ОХОТА ещё раз",Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(),42)
                }
            }
        }
        findViewById<Button>(R.id.testButton).setOnClickListener{alert()}
    }

    override fun onStart(){
        super.onStart()
        val f=IntentFilter().apply{addAction(HuntService.ACTION_STATE);addAction(HuntService.ACTION_DIAGNOSTIC)}
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver,f)
        sendBroadcast(Intent(HuntService.ACTION_QUERY_STATE).setPackage(packageName))
    }
    override fun onStop(){super.onStop();try{unregisterReceiver(receiver)}catch(_:Exception){}}

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==42 && resultCode==Activity.RESULT_OK && data!=null){
            startForegroundService(Intent(this,HuntService::class.java).putExtra(HuntService.EXTRA_RESULT_CODE,resultCode).putExtra(HuntService.EXTRA_DATA,data))
            setRunning(true); status.text="Охота включена • ищу метки…"
        }
    }
    private fun setRunning(v:Boolean){running=v;status.text=if(v)"Охота включена" else "Охота выключена";huntButton.text=if(v)"СТОП" else "ОХОТА"}
    private fun alert(){val vib=getSystemService(Vibrator::class.java);if(Build.VERSION.SDK_INT>=26)vib.vibrate(VibrationEffect.createOneShot(650,VibrationEffect.DEFAULT_AMPLITUDE))else @Suppress("DEPRECATION") vib.vibrate(650);tts?.speak("Лёш, заказ!",TextToSpeech.QUEUE_FLUSH,null,"order")}
    override fun onInit(code:Int){if(code==TextToSpeech.SUCCESS)tts?.language=Locale("ru","RU")}
    override fun onDestroy(){tts?.shutdown();super.onDestroy()}
}
