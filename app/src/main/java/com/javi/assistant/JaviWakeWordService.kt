package com.javi.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var busy = false
    private var stoppedByUser = false
    private var listening = false
    private var restartJob: Job? = null
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Iniciando micrófono…"))
        acquireWakeLock()

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "DO")
                tts?.setPitch(0.84f)
                tts?.setSpeechRate(0.96f)
            }
        }

        createRecognizer()
        restartListening(250, force = true)

        scope.launch {
            while (isActive && !stoppedByUser) {
                delay(3000)
                if (!busy && !listening) {
                    updateNotification("Reconectando micrófono…")
                    restartListening(50, force = true)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stoppedByUser = true
            JaviConfig.setWakeEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        stoppedByUser = false
        JaviConfig.setWakeEnabled(this, true)
        acquireWakeLock()
        if (!busy && !listening) restartListening(100, force = true)
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JAVI:BackgroundListening").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}

        recognizer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: Exception) {
            SpeechRecognizer.createSpeechRecognizer(this)
        }.also { it.setRecognitionListener(this) }

        listening = false
    }

    private fun restartListening(delayMs: Long = 500, force: Boolean = false) {
        if (stoppedByUser || busy) return
        if (!force && listening) return

        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            if (stoppedByUser || busy) return@launch

            try {
                if (force) {
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    listening = false
                }

                recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-DO")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                })
            } catch (_: Exception) {
                listening = false
                updateNotification("Reconectando micrófono…")
                recreateAndRestart(900)
            }
        }
    }

    private fun recreateAndRestart(delayMs: Long = 700) {
        if (stoppedByUser || busy) return
        scope.launch {
            delay(delayMs)
            if (stoppedByUser || busy) return@launch
            createRecognizer()
            restartListening(100, force = true)
        }
    }

    private fun handlePhrase(text: String) {
        listening = false
        if (!CommandRouter.hasWakeWord(text)) {
            restartListening(120, force = true)
            return
        }

        val commandText = CommandRouter.removeWakeWord(text)
        if (commandText.isBlank()) {
            respond("Te escucho")
            return
        }

        busy = true
        updateNotification("Ejecutando: $commandText")
        try { recognizer?.cancel() } catch (_: Exception) {}

        scope.launch {
            try {
                when (val command = CommandRouter.route(commandText)) {
                    JaviCommand.WakePc -> {
                        val mac = JaviConfig.pcMac(this@JaviWakeWordService)
                        if (mac.isBlank()) respond("Primero configura la dirección MAC de tu computadora en Javi.")
                        else {
                            val ok = WakeOnLan.send(mac, JaviConfig.pcBroadcast(this@JaviWakeWordService))
                            respond(if (ok) "Activando el computador." else "No pude enviar la señal al computador.")
                        }
                    }

                    is JaviCommand.AskCore -> {
                        val reply = try {
                            ApiClient.sendMessage(listOf(ChatMessage("user", command.text)))
                        } catch (_: Exception) {
                            "No pude comunicarme con el núcleo de Javi."
                        }
                        respond(reply)
                    }

                    is JaviCommand.OpenApp -> {
                        val match = try {
                            PhoneActions.openAppByName(this@JaviWakeWordService, command.appName)
                        } catch (_: Exception) {
                            null
                        }
                        respond(
                            if (match != null) "Abriendo ${match.label}."
                            else "No encontré ${command.appName} entre tus aplicaciones instaladas."
                        )
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
            } catch (_: Exception) {
                busy = false
                updateNotification("Reconectando micrófono…")
                restartListening(250, force = true)
            }
        }
    }

    private fun respond(text: String) {
        if (JaviConfig.voiceEnabled(this)) {
            speak(text)
        } else {
            busy = false
            restartListening(250, force = true)
        }
    }

    private fun speak(text: String) {
        busy = true
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}

        val utteranceId = "javi-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                scope.launch {
                    busy = false
                    restartListening(300, force = true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                scope.launch {
                    busy = false
                    recreateAndRestart(300)
                }
            }
        })

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR || result == null) {
            busy = false
            recreateAndRestart(350)
        }
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, JaviWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.V.I. · segundo plano")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(text))
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "J.A.V.I. segundo plano",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        consecutiveErrors = 0
        val candidates = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .filter { it.isNotBlank() }

        val phrase = candidates.firstOrNull { CommandRouter.hasWakeWord(it) }
            ?: candidates.firstOrNull()
            .orEmpty()

        if (phrase.isNotBlank()) handlePhrase(phrase)
        else restartListening(200, force = true)
    }

    override fun onError(error: Int) {
        listening = false
        if (stoppedByUser || busy) return

        consecutiveErrors++
        updateNotification("Reconectando micrófono…")
        when {
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> recreateAndRestart(700)
            error == SpeechRecognizer.ERROR_CLIENT -> recreateAndRestart(700)
            error == SpeechRecognizer.ERROR_SERVER -> recreateAndRestart(1000)
            consecutiveErrors >= 3 -> {
                consecutiveErrors = 0
                recreateAndRestart(900)
            }
            else -> restartListening(250, force = true)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        listening = true
        consecutiveErrors = 0
        updateNotification("Escuchando · di “Javi…”")
    }

    override fun onBeginningOfSpeech() {
        listening = true
        updateNotification("Te estoy oyendo…")
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        listening = false
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .firstOrNull()
            .orEmpty()
        if (partial.isNotBlank()) listening = true
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        stoppedByUser = true
        restartJob?.cancel()
        scope.cancel()
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JaviWakeWordService::class.java))
            JaviConfig.setWakeEnabled(context, false)
        }
    }
}
