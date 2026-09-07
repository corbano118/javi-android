package com.javi.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), RecognitionListener {
    private lateinit var recognizer: SpeechRecognizer; private lateinit var tts: TextToSpeech
    private val messages = mutableStateListOf<ChatMessage>()
    private var status by mutableStateOf("LISTO"); private var heard by mutableStateOf(""); private var listening by mutableStateOf(false); private var wakeMode by mutableStateOf(false); private var pcMac by mutableStateOf(""); private var pcBroadcast by mutableStateOf("255.255.255.255")
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() else status = "MICRÓFONO BLOQUEADO" }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) enableWakeMode() else status = "ACTIVA NOTIFICACIONES PARA ASSISTANT MODE" }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { status = if (isAssistantRoleHeld()) "J.A.V.I. ES TU ASISTENTE" else "ASISTENTE NO CAMBIADO" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); pcMac = JaviConfig.pcMac(this); pcBroadcast = JaviConfig.pcBroadcast(this); wakeMode = JaviConfig.wakeEnabled(this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale("es", "DO") }
        setContent { Screen() }; if (intent.getBooleanExtra("assistant_invocation", false)) startVoice()
    }
    @Composable private fun Screen() {
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FD8E8))) { Surface(Modifier.fillMaxSize(), color = Color(0xFF070B10)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("J.A.V.I.", color = Color.White, style = MaterialTheme.typography.titleLarge); Text(if (listening) "ESCUCHANDO" else status, color = Color(0xFF78FFB4), style = MaterialTheme.typography.labelMedium) }
                Text("Assistant Mode Android · v0.4", color = Color.Gray, style = MaterialTheme.typography.labelSmall); if (heard.isNotBlank()) Text("OÍ: $heard", color = Color.LightGray, modifier = Modifier.padding(vertical = 8.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { startVoice() }) { Text("Hablar") }; OutlinedButton(onClick = { requestAssistantRole() }) { Text(if (isAssistantRoleHeld()) "Asistente ✓" else "Usar como asistente") } }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text("Escuchar “Javi”", color = Color.White); Text("Beta: mantiene un servicio de micrófono activo", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }; Switch(checked = wakeMode, onCheckedChange = { setWakeMode(it) }) }
                HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text("COMPUTADORA", color = Color(0xFF4FD8E8), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = pcMac, onValueChange = { pcMac = it }, label = { Text("MAC · ej. AA:BB:CC:DD:EE:FF") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pcBroadcast, onValueChange = { pcBroadcast = it }, label = { Text("Broadcast · ej. 192.168.1.255") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { JaviConfig.savePc(this@MainActivity, pcMac, pcBroadcast); status = "PC GUARDADA" }) { Text("Guardar PC") }; Button(onClick = { JaviConfig.savePc(this@MainActivity, pcMac, pcBroadcast); lifecycleScope.launch { wakePc() } }) { Text("Activar PC") } }
                HorizontalDivider(Modifier.padding(vertical = 12.dp)); LazyColumn(Modifier.weight(1f)) { items(messages) { m -> Text((if (m.role == "user") "Tú: " else "J.A.V.I.: ") + m.content, color = Color.White, modifier = Modifier.padding(vertical = 6.dp)) } }
            }
        } }
    }
    private fun isAssistantRoleHeld(): Boolean { if (Build.VERSION.SDK_INT < 29) return false; val rm = getSystemService(RoleManager::class.java); return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }
    private fun requestAssistantRole() { if (Build.VERSION.SDK_INT >= 29) { val rm = getSystemService(RoleManager::class.java); if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) assistantRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) else status = if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) "J.A.V.I. YA ES TU ASISTENTE" else "ROL DE ASISTENTE NO DISPONIBLE" } }
    private fun setWakeMode(enabled: Boolean) {
        if (!enabled) { JaviWakeWordService.stop(this); wakeMode = false; status = "ASSISTANT MODE DETENIDO"; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micPermission.launch(Manifest.permission.RECORD_AUDIO); status = "CONCEDE MICRÓFONO Y ACTIVA DE NUEVO"; return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return }; enableWakeMode()
    }
    private fun enableWakeMode() { JaviWakeWordService.start(this); JaviConfig.setWakeEnabled(this, true); wakeMode = true; status = "ASSISTANT MODE ACTIVO" }
    private fun startVoice() { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    private fun listen() { listening = true; status = "ESCUCHANDO"; recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) }) }
    private fun process(text: String) { val raw = text.trim(); if (raw.isBlank()) return; val commandText = CommandRouter.removeWakeWord(raw); if (commandText.isBlank()) { speak("Te escucho."); return }; lifecycleScope.launch { executeCommand(CommandRouter.route(commandText)) } }
    private suspend fun executeCommand(command: JaviCommand) { when (command) { JaviCommand.WakePc -> wakePc(); is JaviCommand.OpenApp -> { val ok = PhoneActions.openApp(this, command.packageName); speak(if (ok) "Abriendo ${command.spokenName}." else "No encontré ${command.spokenName} instalado.") }; is JaviCommand.SetAlarm -> { PhoneActions.setAlarm(this, command.hour, command.minute); speak("Preparando la alarma.") }; is JaviCommand.AskCore -> askCore(command.text) } }
    private suspend fun wakePc() { val mac = JaviConfig.pcMac(this); if (mac.isBlank()) { speak("Primero configura la dirección MAC de tu computadora."); return }; status = "ACTIVANDO PC"; val ok = WakeOnLan.send(mac, JaviConfig.pcBroadcast(this)); speak(if (ok) "Activando el computador." else "No pude enviar la señal al computador.") }
    private suspend fun askCore(command: String) { messages += ChatMessage("user", command); status = "PENSANDO"; val reply = ApiClient.sendMessage(messages.toList()); messages += ChatMessage("assistant", reply); speak(reply) }
    private fun speak(text: String) { listening = false; status = "RESPONDIENDO"; try { recognizer.cancel() } catch (_: Exception) {}; tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "javi-reply"); status = "LISTO" }
    override fun onResults(r: Bundle?) { listening = false; val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); heard = text; process(text) }
    override fun onPartialResults(r: Bundle?) { heard = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
    override fun onError(error: Int) { listening = false; status = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "NO TE ESCUCHÉ" else "ERROR DE VOZ $error" }
    override fun onReadyForSpeech(params: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(rmsdB: Float) {}; override fun onBufferReceived(buffer: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onDestroy() { recognizer.destroy(); tts.shutdown(); super.onDestroy() }
}
