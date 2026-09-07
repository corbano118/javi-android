package com.javi.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale

class JaviWakeWordService : Service(), RecognitionListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var busy = false
    private var stoppedByUser = false

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Escuchando · di “Javi…”"))
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "DO")
                tts?.setPitch(0.84f)
                tts?.setSpeechRate(0.96f)
            }
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        restartListening(250)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stoppedByUser = true
            JaviConfig.setWakeEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        JaviConfig.setWakeEnabled(this, true)
        return START_STICKY
    }

    private fun restartListening(delayMs: Long = 500) {
        if (stoppedByUser || busy) return
        scope.launch {
            delay(delayMs)
            if (stoppedByUser || busy) return@launch
            try {
                recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                })
            } catch (_: Exception) { restartListening(1200) }
        }
    }

    private fun handlePhrase(text: String) {
        if (!CommandRouter.hasWakeWord(text)) { restartListening(250); return }
        val commandText = CommandRouter.removeWakeWord(text)
        if (commandText.isBlank()) { respond("Te escucho"); return }
        busy = true
        scope.launch {
            when (val command = CommandRouter.route(commandText)) {
                JaviCommand.WakePc -> {
                    val mac = JaviConfig.pcMac(this@JaviWakeWordService)
                    if (mac.isBlank()) respond("Primero configura la dirección MAC de tu computadora en Javi.")
                    else {
                        val ok = WakeOnLan.send(mac, JaviConfig.pcBroadcast(this@JaviWakeWordService))
                        respond(if (ok) "Activando el computador." else "No pude enviar la señal al computador.")
                    }
                }
                is JaviCommand.AskCore -> respond(ApiClient.sendMessage(listOf(ChatMessage("user", command.text))))
                is JaviCommand.OpenApp -> {
                    val match = try { PhoneActions.openAppByName(this@JaviWakeWordService, command.appName) } catch (_: Exception) { null }
                    respond(if (match != null) "Abriendo ${match.label}." else "No encontré ${command.appName} entre tus aplicaciones instaladas.")
                }
                is JaviCommand.SetAlarm -> {
                    try {
                        PhoneActions.setAlarm(this@JaviWakeWordService, command.hour, command.minute)
                        respond("Preparando la alarma.")
                    } catch (_: Exception) {
                        respond("No pude abrir la aplicación de alarma.")
                    }
                }
            }
        }
    }

    private fun respond(text: String) {
        if (JaviConfig.voiceEnabled(this)) speak(text) else {
            busy = false
            restartListening(350)
        }
    }

    private fun speak(text: String) {
        busy = true
        try { recognizer?.cancel() } catch (_: Exception) {}
        val utteranceId = "javi-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { busy = false; restartListening(500) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { scope.launch { busy = false; restartListening(700) } }
        })
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) {
            busy = false
            restartListening(700)
        }
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(this, 2, Intent(this, JaviWakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.V.I. Assistant Mode")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "J.A.V.I. Assistant Mode", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull().orEmpty()
        if (text.isNotBlank()) handlePhrase(text) else restartListening(300)
    }
    override fun onError(error: Int) { if (!stoppedByUser && !busy) restartListening(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 450) }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        stoppedByUser = true
        scope.cancel()
        try { recognizer?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.javi.assistant.STOP_WAKE"
        private const val CHANNEL_ID = "javi_wake"
        private const val NOTIFICATION_ID = 704
        fun start(context: Context) {
            val i = Intent(context, JaviWakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, JaviWakeWordService::class.java))
            JaviConfig.setWakeEnabled(context, false)
        }
    }
}
