package ru.hapuga.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
    private var tts: TextToSpeech? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HuntService.ACTION_NEW_ORDER -> alert()
                HuntService.ACTION_DIAGNOSTIC -> {
                    val n = intent.getIntExtra(HuntService.EXTRA_MARKER_COUNT, 0)
                    status.text = "Охота включена • меток: $n"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        status = findViewById(R.id.status)
        tts = TextToSpeech(this, this)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)

        findViewById<Button>(R.id.huntButton).setOnClickListener {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), 42)
        }
        findViewById<Button>(R.id.testButton).setOnClickListener { alert() }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(HuntService.ACTION_NEW_ORDER)
            addAction(HuntService.ACTION_DIAGNOSTIC)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, f)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, HuntService::class.java)
                .putExtra(HuntService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(HuntService.EXTRA_DATA, data)
            startForegroundService(i)
            status.text = "Охота включена • ищу метки…"
        }
    }

    private fun alert() {
        val vib = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(650, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vib.vibrate(650)
        tts?.speak("Лёш, заказ!", TextToSpeech.QUEUE_FLUSH, null, "order")
    }

    override fun onInit(code: Int) { if (code == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU") }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}
