package com.javi.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), RecognitionListener {
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val messages = mutableStateListOf<ChatMessage>()
    private var status by mutableStateOf("LISTO")
    private var heard by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var alwaysListening by mutableStateOf(false)
    private var voiceEnabled by mutableStateOf(true)
    private var textInput by mutableStateOf("")
    private var showSettings by mutableStateOf(false)
    private var pcMac by mutableStateOf("")
    private var pcBroadcast by mutableStateOf("255.255.255.255")
    private var pendingBackgroundActivation = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { if (pendingBackgroundActivation) continueBackgroundSetup() else listen() }
        else { pendingBackgroundActivation = false; status = "PERMISO DE MICRÓFONO NECESARIO" }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enableAlwaysListening() else { pendingBackgroundActivation = false; status = "PERMITE NOTIFICACIONES PARA MANTENER J.A.V.I. ACTIVO" }
    }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = if (isAssistantRoleHeld()) "J.A.V.I. ES TU ASISTENTE" else "PUEDES ACTIVARLO DESPUÉS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        pcMac = JaviConfig.pcMac(this)
        pcBroadcast = JaviConfig.pcBroadcast(this)
        alwaysListening = JaviConfig.wakeEnabled(this)
        voiceEnabled = JaviConfig.voiceEnabled(this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts.language = Locale("es", "DO")
                tts.setPitch(0.84f)
                tts.setSpeechRate(0.96f)
            }
        }
        setContent { JaviScreen() }
        if (alwaysListening && hasMicPermission()) try { JaviWakeWordService.start(this) } catch (_: Exception) {}
        if (intent.getBooleanExtra("assistant_invocation", false)) startVoice()
    }

    @Composable private fun JaviScreen() {
        val bg = Color(0xFF05080D); val panel = Color(0xFF0D131B); val cyan = Color(0xFF55E6F2); val green = Color(0xFF78FFB4)
        MaterialTheme(colorScheme = darkColorScheme(primary = cyan, background = bg, surface = panel)) {
            Surface(Modifier.fillMaxSize(), color = bg) {
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("J.A.V.I.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light); Text("Tu asistente personal", color = Color(0xFF8B96A5), fontSize = 13.sp) }
                        Surface(shape = RoundedCornerShape(18.dp), color = if (alwaysListening) Color(0xFF123324) else Color(0xFF151A22)) {
                            Text(if (alwaysListening) "ACTIVO" else "LISTO", color = if (alwaysListening) green else Color(0xFFB8C0CB), modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                    Box(Modifier.size(170.dp).background(if (listening) Color(0xFF173A42) else Color(0xFF0F202A), CircleShape).clickable { startVoice() }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(126.dp).background(cyan.copy(alpha = if (listening) 0.28f else 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                            Text(if (listening) "•••" else "J", color = cyan, fontSize = if (listening) 38.sp else 54.sp, fontWeight = FontWeight.Light)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(when { listening -> "Te escucho…"; status == "PENSANDO" -> "Pensando…"; status == "RESPONDIENDO" -> "Respondiendo…"; else -> "Di “Javi” y luego tu comando" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    if (heard.isNotBlank()) Text(heard, color = Color(0xFF9CA9B8), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text("Escuchar “Javi”", color = Color.White, fontWeight = FontWeight.SemiBold); Text(if (alwaysListening) "J.A.V.I. permanece disponible en segundo plano" else "Actívalo para llamarlo sin abrir la app", color = Color(0xFF8D99A8), fontSize = 12.sp) }
                            Switch(checked = alwaysListening, onCheckedChange = { changeAlwaysListening(it) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallAction("Asistente", Modifier.weight(1f)) { requestAssistantRole() }; SmallAction("Segundo plano", Modifier.weight(1f)) { openBatterySettings() }; SmallAction("Ajustes", Modifier.weight(1f)) { showSettings = true }
                    }
                    if (messages.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(messages) { message ->
                                val user = message.role == "user"
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                                    Surface(color = if (user) Color(0xFF13313A) else panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.88f)) { Text(message.content, color = Color.White, modifier = Modifier.padding(12.dp), fontSize = 14.sp) }
                                }
                            }
                        }
                    } else Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = textInput, onValueChange = { textInput = it }, placeholder = { Text("Escríbele a J.A.V.I.") }, singleLine = true, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp)); Button(onClick = { sendTypedMessage() }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(54.dp)) { Text("→", fontSize = 24.sp) }
                    }
                }
                if (showSettings) SettingsDialog()
            }
        }
    }

    @Composable private fun SmallAction(label: String, modifier: Modifier, onClick: () -> Unit) { OutlinedButton(onClick = onClick, modifier = modifier.height(46.dp), contentPadding = PaddingValues(horizontal = 5.dp)) { Text(label, fontSize = 11.sp, maxLines = 1) } }

    @Composable private fun SettingsDialog() {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Configuración") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("Responder con voz", fontWeight = FontWeight.SemiBold); Text(if (voiceEnabled) "J.A.V.I. hablará al responder" else "Solo mostrará respuestas en pantalla", color = Color.Gray, fontSize = 12.sp) }
                        Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it; JaviConfig.setVoiceEnabled(this@MainActivity, it); if (!it) tts.stop() })
                    }
                    HorizontalDivider()
                    Text("La conexión con computadora queda guardada aquí para cuando la utilices.", color = Color.Gray, fontSize = 12.sp)
                    OutlinedTextField(value = pcMac, onValueChange = { pcMac = it }, label = { Text("MAC de la computadora") }, singleLine = true)
                    OutlinedTextField(value = pcBroadcast, onValueChange = { pcBroadcast = it }, label = { Text("Broadcast de red") }, singleLine = true)
                }
            },
            confirmButton = { Button(onClick = { JaviConfig.savePc(this, pcMac, pcBroadcast); status = "CONFIGURACIÓN GUARDADA"; showSettings = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cerrar") } }
        )
    }

    private fun sendTypedMessage() { val text = textInput.trim(); if (text.isBlank()) return; textInput = ""; process(text) }
    private fun hasMicPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun changeAlwaysListening(enabled: Boolean) { if (!enabled) { JaviWakeWordService.stop(this); alwaysListening = false; status = "LISTO"; return }; pendingBackgroundActivation = true; if (!hasMicPermission()) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return }; continueBackgroundSetup() }
    private fun continueBackgroundSetup() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return }; enableAlwaysListening() }
    private fun enableAlwaysListening() { pendingBackgroundActivation = false; try { JaviWakeWordService.start(this); JaviConfig.setWakeEnabled(this, true); alwaysListening = true; status = "J.A.V.I. ACTIVO EN SEGUNDO PLANO" } catch (_: Exception) { alwaysListening = false; status = "NO PUDE INICIAR EL MODO EN SEGUNDO PLANO" } }
    private fun openBatterySettings() { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); status = "PERMITE QUE J.A.V.I. FUNCIONE SIN RESTRICCIONES" } catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    private fun isAssistantRoleHeld(): Boolean { if (Build.VERSION.SDK_INT < 29) return false; val rm = getSystemService(RoleManager::class.java); return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }
    private fun requestAssistantRole() { if (Build.VERSION.SDK_INT < 29) return; val rm = getSystemService(RoleManager::class.java); if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) assistantRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) else status = if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) "J.A.V.I. YA ES TU ASISTENTE" else "ROL DE ASISTENTE NO DISPONIBLE" }
    private fun startVoice() { if (hasMicPermission()) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    private fun listen() { listening = true; status = "ESCUCHANDO"; try { recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) }) } catch (_: Exception) { listening = false; status = "NO PUDE INICIAR EL MICRÓFONO" } }
    private fun process(text: String) { val raw = text.trim(); if (raw.isBlank()) return; val commandText = CommandRouter.removeWakeWord(raw); if (commandText.isBlank()) { speak("Te escucho."); return }; lifecycleScope.launch { executeCommand(CommandRouter.route(commandText)) } }

    private suspend fun executeCommand(command: JaviCommand) {
        when (command) {
            JaviCommand.WakePc -> wakePc()
            is JaviCommand.OpenApp -> {
                status = "ABRIENDO APP"
                val match = try { PhoneActions.openAppByName(this, command.appName) } catch (_: Exception) { null }
                speak(if (match != null) "Abriendo ${match.label}." else "No encontré ${command.appName} entre tus aplicaciones instaladas.")
            }
            is JaviCommand.SetAlarm -> {
                try { PhoneActions.setAlarm(this, command.hour, command.minute); speak("Preparando la alarma.") }
                catch (_: Exception) { speak("No pude abrir la aplicación de alarma.") }
            }
            is JaviCommand.AskCore -> askCore(command.text)
        }
    }

    private suspend fun wakePc() { val mac = JaviConfig.pcMac(this); if (mac.isBlank()) { speak("Primero configura la dirección MAC de tu computadora."); return }; status = "ACTIVANDO PC"; val ok = WakeOnLan.send(mac, JaviConfig.pcBroadcast(this)); speak(if (ok) "Activando el computador." else "No pude enviar la señal al computador.") }
    private suspend fun askCore(command: String) { messages += ChatMessage("user", command); status = "PENSANDO"; val reply = ApiClient.sendMessage(messages.toList()); messages += ChatMessage("assistant", reply); speak(reply) }

    private fun speak(text: String) {
        listening = false
        try { recognizer.cancel() } catch (_: Exception) {}
        if (!voiceEnabled) { status = "LISTO"; return }
        status = "RESPONDIENDO"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "javi-reply")
        status = "LISTO"
    }

    override fun onResults(results: Bundle?) { listening = false; val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); heard = text; process(text) }
    override fun onPartialResults(partialResults: Bundle?) { heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
    override fun onError(error: Int) { listening = false; status = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "NO TE ESCUCHÉ" else "ERROR DE VOZ $error" }
    override fun onReadyForSpeech(params: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(rmsdB: Float) {}; override fun onBufferReceived(buffer: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onDestroy() { recognizer.destroy(); tts.shutdown(); super.onDestroy() }
}
