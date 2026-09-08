package com.javi.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

/**
 * Escucha persistente de JAVI v0.13.
 *
 * IMPORTANTE: SpeechRecognizer de Android NO se mantiene escuchando todo el día.
 * Vosk mantiene un detector LOCAL y OFFLINE limitado a la palabra "javi".
 * SpeechRecognizer se crea únicamente después de detectar el wake word para
 * transcribir una sola orden y se destruye al terminar.
 */
class JaviWakeWordService : Service(), org.vosk.android.RecognitionListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeModel: Model? = null
    private var wakeSpeech: SpeechService? = null
    private var commandRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stoppedByUser = false
    private var commandMode = false
    private var wakeStarting = false

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparando detector local de Javi…"))
        acquireWakeLock()

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "DO")
                tts?.setPitch(0.84f)
                tts?.setSpeechRate(0.96f)
            }
        }

        loadWakeModel()
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
        if (wakeModel != null && !commandMode && wakeSpeech == null) startWakeListening()
        return START_STICKY
    }

    private fun loadWakeModel() {
        if (wakeStarting || stoppedByUser) return
        wakeStarting = true
        StorageService.unpack(
            this,
            "model-es",
            "javi-vosk-es",
            { model ->
                wakeStarting = false
                wakeModel = model
                startWakeListening()
            },
            { error ->
                wakeStarting = false
                updateNotification("Error cargando escucha local")
                scope.launch {
                    delay(2500)
                    if (!stoppedByUser) loadWakeModel()
                }
            }
        )
    }

    private fun startWakeListening() {
        if (stoppedByUser || commandMode || wakeSpeech != null) return
        val model = wakeModel ?: return
        try {
            // Gramática mínima: el motor local solo necesita reconocer el nombre Javi.
            val recognizer = Recognizer(model, 16000.0f, "[\"javi\", \"[unk]\"]")
            wakeSpeech = SpeechService(recognizer, 16000.0f).also {
                it.startListening(this)
            }
            updateNotification("Escuchando localmente · di “Javi”")
        } catch (_: Exception) {
            wakeSpeech = null
            updateNotification("Reiniciando escucha local…")
            scope.launch {
                delay(1000)
                startWakeListening()
            }
        }
    }

    private fun stopWakeListening() {
        try { wakeSpeech?.stop() } catch (_: Exception) {}
        try { wakeSpeech?.shutdown() } catch (_: Exception) {}
        wakeSpeech = null
    }

    private fun voskText(json: String): String {
        return Regex("\\\"(?:partial|text)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            .find(json)?.groupValues?.getOrNull(1).orEmpty().lowercase(Locale.ROOT)
    }

    private fun inspectWake(json: String) {
        if (commandMode || stoppedByUser) return
        val text = voskText(json)
        if (text.split(' ').any { it == "javi" }) activateCommandMode()
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis != null) inspectWake(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis != null) inspectWake(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis != null) inspectWake(hypothesis)
    }

    override fun onError(exception: Exception?) {
        if (stoppedByUser || commandMode) return
        stopWakeListening()
        scope.launch { delay(700); startWakeListening() }
    }

    override fun onTimeout() {
        if (stoppedByUser || commandMode) return
        stopWakeListening()
        scope.launch { delay(150); startWakeListening() }
    }

    private fun activateCommandMode() {
        if (commandMode || stoppedByUser) return
        commandMode = true
        stopWakeListening()
        updateNotification("Javi activado · dime la orden")
        listenForOneCommand()
    }

    private fun listenForOneCommand() {
        destroyCommandRecognizer()
        commandRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { updateNotification("Te escucho…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    destroyCommandRecognizer()
                    if (text.isBlank()) returnToWake()
                    else executeCommand(text)
                }

                override fun onError(error: Int) {
                    destroyCommandRecognizer()
                    speakAndReturn("No entendí la orden.")
                }
            })
        }

        try {
            commandRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-DO")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            })
        } catch (_: Exception) {
            destroyCommandRecognizer()
            speakAndReturn("No pude iniciar el reconocimiento de la orden.")
        }
    }

    private fun executeCommand(text: String) {
        updateNotification("Ejecutando: $text")
        scope.launch {
            val reply = try {
                when (val command = CommandRouter.route(text)) {
                    JaviCommand.WakePc -> {
                        val mac = JaviConfig.pcMac(this@JaviWakeWordService)
                        if (mac.isBlank()) "Primero configura la dirección MAC de tu computadora en Javi."
                        else if (WakeOnLan.send(mac, JaviConfig.pcBroadcast(this@JaviWakeWordService)))
                            "Activando el computador."
                        else "No pude enviar la señal al computador."
                    }
                    is JaviCommand.AskCore -> try {
                        ApiClient.sendMessage(listOf(ChatMessage("user", command.text)))
                    } catch (_: Exception) { "No pude comunicarme con el núcleo de Javi." }
                    is JaviCommand.OpenApp -> {
                        val match = try { PhoneActions.openAppByName(this@JaviWakeWordService, command.appName) } catch (_: Exception) { null }
                        if (match != null) "Abriendo ${match.label}."
                        else "No encontré ${command.appName} entre tus aplicaciones instaladas."
                    }
                    is JaviCommand.SetAlarm -> try {
                        PhoneActions.setAlarm(this@JaviWakeWordService, command.hour, command.minute)
                        "Preparando la alarma."
                    } catch (_: Exception) { "No pude abrir la aplicación de alarma." }
                }
            } catch (_: Exception) {
                "Ocurrió un problema ejecutando la orden."
            }
            speakAndReturn(reply)
        }
    }

    private fun speakAndReturn(text: String) {
        if (!JaviConfig.voiceEnabled(this)) {
            returnToWake()
            return
        }
        val id = "javi-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { scope.launch { returnToWake() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { scope.launch { returnToWake() } }
        })
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r == null || r == TextToSpeech.ERROR) returnToWake()
    }

    private fun returnToWake() {
        destroyCommandRecognizer()
        commandMode = false
        if (!stoppedByUser) startWakeListening()
    }

    private fun destroyCommandRecognizer() {
        try { commandRecognizer?.cancel() } catch (_: Exception) {}
        try { commandRecognizer?.destroy() } catch (_: Exception) {}
        commandRecognizer = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JAVI:LocalWakeWord").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, JaviWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.V.I. · wake word local")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "J.A.V.I. wake word local", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stoppedByUser = true
        stopWakeListening()
        destroyCommandRecognizer()
        try { wakeModel?.close() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.javi.assistant.STOP_WAKE"
        private const val CHANNEL_ID = "javi_wake"
        private const val NOTIFICATION_ID = 704

        fun start(context: Context) {
            val i = Intent(context, JaviWakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JaviWakeWordService::class.java))
            JaviConfig.setWakeEnabled(context, false)
        }
    }
}
