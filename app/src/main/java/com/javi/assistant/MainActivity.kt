package com.javi.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), RecognitionListener {

    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech

    private val messages = mutableStateListOf<ChatMessage>()
    private var textInput by mutableStateOf("")
    private var status by mutableStateOf("Listo")
    private var listening by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var voiceEnabled by mutableStateOf(true)
    private var selectedImageUri by mutableStateOf<Uri?>(null)
    private var selectedImageName by mutableStateOf<String?>(null)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else status = "Necesito permiso de micrófono"
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
        selectedImageName = uri?.let { resolveName(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        JaviConfig.setWakeEnabled(this, false)
        try { JaviWakeWordService.stop(this) } catch (_: Exception) {}

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        voiceEnabled = JaviConfig.voiceEnabled(this)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts.language = Locale("es", "DO")
                tts.setPitch(0.92f)
                tts.setSpeechRate(0.98f)
            }
        }

        setContent { JaviChatApp() }
    }

    @Composable
    private fun JaviChatApp() {
        val bg = Color(0xFF111318)
        val panel = Color(0xFF1A1D23)
        val accent = Color(0xFF73E6D3)

        MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel)) {
            Surface(modifier = Modifier.fillMaxSize(), color = bg) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(38.dp).background(accent.copy(alpha = .16f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text("J", color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("J.A.V.I.", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                                Text(if (thinking) "Pensando…" else status, color = Color(0xFF979DA8), fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = {
                            voiceEnabled = !voiceEnabled
                            JaviConfig.setVoiceEnabled(this@MainActivity, voiceEnabled)
                            if (!voiceEnabled) tts.stop()
                        }) { Text(if (voiceEnabled) "🔊" else "🔇", fontSize = 20.sp) }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = .06f))

                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier.size(84.dp).background(accent.copy(alpha = .12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text("J", color = accent, fontSize = 39.sp, fontWeight = FontWeight.Light) }
                            Spacer(Modifier.height(18.dp))
                            Text("¿En qué puedo ayudarte?", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Escribe o habla conmigo. También puedes adjuntar una foto desde el botón +.",
                                color = Color(0xFF9BA2AD), fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(messages) { _, message ->
                                MessageBubble(message)
                            }
                        }
                    }

                    selectedImageName?.let { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(color = panel, shape = RoundedCornerShape(14.dp)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🖼️", fontSize = 18.sp)
                                    Spacer(Modifier.width(7.dp))
                                    Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1)
                                    Spacer(Modifier.width(8.dp))
                                    Text("✕", color = Color(0xFFB8BEC8), modifier = Modifier.clickable {
                                        selectedImageUri = null
                                        selectedImageName = null
                                    })
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        FilledTonalIconButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.size(48.dp)) {
                            Text("+", fontSize = 27.sp)
                        }
                        Spacer(Modifier.width(7.dp))
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Mensaje a J.A.V.I.") },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5
                        )
                        Spacer(Modifier.width(7.dp))
                        FilledTonalIconButton(onClick = { startVoice() }, modifier = Modifier.size(48.dp)) {
                            Text(if (listening) "••" else "🎤", fontSize = if (listening) 15.sp else 19.sp)
                        }
                        Spacer(Modifier.width(5.dp))
                        Button(
                            onClick = { sendTypedMessage() },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            enabled = !thinking && (textInput.isNotBlank() || selectedImageUri != null)
                        ) { Text("↑", fontSize = 23.sp) }
                    }
                }
            }
        }
    }

    @Composable
    private fun MessageBubble(message: ChatMessage) {
        val user = message.role == "user"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(if (user) .86f else .94f),
                color = if (user) Color(0xFF263238) else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    if (!user) Text("J.A.V.I.", color = Color(0xFF73E6D3), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (!user) Spacer(Modifier.height(3.dp))
                    Text(message.content, color = Color(0xFFF2F3F5), fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
    }

    private fun resolveName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else "Imagen"
            } ?: "Imagen"
        } catch (_: Exception) { "Imagen" }
    }

    private fun sendTypedMessage() {
        val text = textInput.trim()
        val image = selectedImageUri
        if (text.isBlank() && image == null) return

        textInput = ""
        if (image != null) {
            val name = selectedImageName ?: "imagen"
            val prompt = if (text.isBlank()) "He adjuntado una imagen: $name" else "$text\n\n[Imagen adjunta: $name]"
            selectedImageUri = null
            selectedImageName = null
            sendToJavi(prompt)
        } else sendToJavi(text)
    }

    private fun sendToJavi(text: String) {
        messages += ChatMessage("user", text)
        thinking = true
        status = "Pensando…"
        lifecycleScope.launch {
            val reply = try { ApiClient.sendMessage(messages.toList()) }
            catch (_: Exception) { "No pude conectarme ahora mismo. Inténtalo otra vez." }
            messages += ChatMessage("assistant", reply)
            thinking = false
            status = "Listo"
            speak(reply)
        }
    }

    private fun hasMicPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoice() {
        if (hasMicPermission()) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun listen() {
        if (listening) return
        listening = true
        status = "Escuchando…"
        try {
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        } catch (_: Exception) {
            listening = false
            status = "No pude iniciar el micrófono"
        }
    }

    private fun speak(text: String) {
        if (!voiceEnabled) return
        try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "javi-reply-${System.currentTimeMillis()}") } catch (_: Exception) {}
    }

    override fun onResults(results: Bundle?) {
        listening = false
        status = "Listo"
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        if (text.isNotBlank()) sendToJavi(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onError(error: Int) { listening = false; status = "Listo" }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        try { recognizer.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
