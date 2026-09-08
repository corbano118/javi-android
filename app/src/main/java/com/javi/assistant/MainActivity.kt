package com.javi.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

data class UiMessage(val role: String, val content: String, val imageBase64: String? = null)

enum class JaviSection { CHAT, IMAGES, VIDEOS, MUSIC, AVATARS, TASKS }

class MainActivity : ComponentActivity(), RecognitionListener {
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech

    private val messages = mutableStateListOf<UiMessage>()
    private val history = mutableStateListOf<StoredConversation>()
    private val tasks = mutableStateListOf<JaviTask>()

    private var conversationId = ChatStore.newId()
    private var textInput by mutableStateOf("")
    private var status by mutableStateOf("Listo")
    private var listening by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var voiceEnabled by mutableStateOf(true)
    private var selectedImageUri by mutableStateOf<Uri?>(null)
    private var selectedImageName by mutableStateOf<String?>(null)
    private var section by mutableStateOf(JaviSection.CHAT)
    private var drawerOpen by mutableStateOf(false)
    private var studioImageBase64 by mutableStateOf<String?>(null)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) listen() else status = "Necesito permiso de micrófono"
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
        selectedImageName = uri?.let(::resolveName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        history += ChatStore.load(this)
        tasks += TaskStore.load(this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        voiceEnabled = JaviConfig.voiceEnabled(this)
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale("es", "DO")
                tts.setPitch(.92f)
                tts.setSpeechRate(.98f)
            }
        }
        setContent { JaviApp() }
    }

    private fun saveChat() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(42)?.ifBlank { "Conversación" }
            ?: "Conversación"
        ChatStore.save(
            this,
            StoredConversation(conversationId, title, System.currentTimeMillis(), messages.toList())
        )
        history.clear()
        history += ChatStore.load(this)
    }

    private fun newChat() {
        saveChat()
        conversationId = ChatStore.newId()
        messages.clear()
        section = JaviSection.CHAT
        drawerOpen = false
        status = "Listo"
    }

    private fun openChat(conversation: StoredConversation) {
        saveChat()
        conversationId = conversation.id
        messages.clear()
        messages += conversation.messages
        section = JaviSection.CHAT
        drawerOpen = false
    }

    @Composable
    private fun JaviApp() {
        val bg = Color(0xFF101216)
        val panel = Color(0xFF171A20)
        val accent = Color(0xFF72E3D1)
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel)) {
            Box(Modifier.fillMaxSize().background(bg)) {
                Column(Modifier.fillMaxSize()) {
                    Header(accent)
                    when (section) {
                        JaviSection.CHAT -> ChatScreen(Modifier.weight(1f))
                        JaviSection.IMAGES -> StudioScreen(
                            modifier = Modifier.weight(1f),
                            title = "Imágenes",
                            description = "Crea imágenes o adjunta una foto para editarla.",
                            hint = "Ej.: crea un diagrama del sistema solar"
                        )
                        JaviSection.VIDEOS -> StudioScreen(
                            modifier = Modifier.weight(1f),
                            title = "Videos · hasta 1:30",
                            description = "Describe el video que deseas generar.",
                            hint = "Ej.: video educativo de 60 segundos sobre células"
                        )
                        JaviSection.MUSIC -> StudioScreen(
                            modifier = Modifier.weight(1f),
                            title = "Música · hasta 4:00",
                            description = "Describe género, ambiente, instrumentos y letra si la deseas.",
                            hint = "Ej.: canción de estudio lo-fi de 4 minutos"
                        )
                        JaviSection.AVATARS -> StudioScreen(
                            modifier = Modifier.weight(1f),
                            title = "Avatares",
                            description = "Describe tu avatar y elige voz masculina o femenina en el prompt.",
                            hint = "Ej.: profesor virtual joven, voz masculina"
                        )
                        JaviSection.TASKS -> TasksScreen(Modifier.weight(1f))
                    }
                }
                if (drawerOpen) Drawer(accent)
            }
        }
    }

    @Composable
    private fun Header(accent: Color) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { drawerOpen = true }) { Text("☰", fontSize = 25.sp, color = Color.White) }
            Box(
                Modifier.size(38.dp).background(accent.copy(alpha = .16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("J", color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("J.A.V.I.", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                Text(if (thinking) "Pensando…" else status, color = Color(0xFF979DA8), fontSize = 12.sp)
            }
            TextButton(onClick = {
                voiceEnabled = !voiceEnabled
                JaviConfig.setVoiceEnabled(this@MainActivity, voiceEnabled)
                if (!voiceEnabled) tts.stop()
            }) {
                Text(if (voiceEnabled) "🔊" else "🔇", fontSize = 20.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .06f))
    }

    @Composable
    private fun Drawer(accent: Color) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))) {
            Column(
                Modifier.fillMaxHeight().fillMaxWidth(.82f).background(Color(0xFF080909)).padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("J.A.V.I.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { drawerOpen = false }) { Text("✕", color = Color.White) }
                }
                Spacer(Modifier.height(18.dp))
                Menu("＋", "Nuevo chat") { newChat() }
                Menu("💬", "Chats") { section = JaviSection.CHAT; drawerOpen = false }
                Menu("▧", "Imágenes") { section = JaviSection.IMAGES; drawerOpen = false }
                Menu("▶", "Videos") { section = JaviSection.VIDEOS; drawerOpen = false }
                Menu("♫", "Música") { section = JaviSection.MUSIC; drawerOpen = false }
                Menu("◉", "Avatares") { section = JaviSection.AVATARS; drawerOpen = false }
                Menu("✓", "Tareas") { section = JaviSection.TASKS; drawerOpen = false }
                Spacer(Modifier.height(18.dp))
                Text("Historial", color = Color.LightGray, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(history, key = { it.id }) { conversation ->
                        Text(
                            conversation.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().clickable { openChat(conversation) }.padding(vertical = 10.dp)
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = .1f))
                Text(
                    "Los chats se eliminan automáticamente después de 60 días.",
                    color = Color(0xFF9CA2AA),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Text("J.A.V.I. · IA para estudios", color = accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    @Composable
    private fun Menu(icon: String, label: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, color = Color.White, fontSize = 20.sp, modifier = Modifier.width(42.dp))
            Text(label, color = Color.White, fontSize = 18.sp)
        }
    }

    @Composable
    private fun ChatScreen(modifier: Modifier) {
        Column(modifier.fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("J", color = Color(0xFF72E3D1), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(15.dp))
                    Text("¿Qué vamos a estudiar?", color = Color.White, fontSize = 23.sp)
                    Text("Pregunta, resume, practica o analiza tus apuntes.", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(messages) { _, message -> Bubble(message) }
                }
            }
            Composer()
        }
    }

    @Composable
    private fun StudioScreen(modifier: Modifier, title: String, description: String, hint: String) {
        Column(modifier.fillMaxWidth().padding(22.dp)) {
            Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(description, color = Color(0xFFA7ADB6), fontSize = 15.sp)
            if (section == JaviSection.VIDEOS || section == JaviSection.MUSIC || section == JaviSection.AVATARS) {
                Spacer(Modifier.height(18.dp))
                Surface(color = Color(0xFF1B2025), shape = RoundedCornerShape(16.dp)) {
                    Text(
                        "La interfaz ya está lista. Para devolver MP4, audio o avatar real hace falta conectar un proveedor multimedia con credenciales válidas.",
                        color = Color(0xFFBFC5CC),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
            studioImageBase64?.takeIf { section == JaviSection.IMAGES }?.let { data ->
                decodeBitmap(data)?.let { bitmap ->
                    Spacer(Modifier.height(16.dp))
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "Imagen generada",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(hint) },
                shape = RoundedCornerShape(22.dp),
                minLines = 3
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (section == JaviSection.IMAGES) generateStudioImage()
                    else status = "Falta conectar el motor multimedia"
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = textInput.isNotBlank() && !thinking
            ) {
                Text(if (section == JaviSection.IMAGES) "Generar imagen" else "Generar archivo real")
            }
        }
    }

    @Composable
    private fun TasksScreen(modifier: Modifier) {
        var add by remember { mutableStateOf("") }
        Column(modifier.fillMaxWidth().padding(20.dp)) {
            Text("Tareas", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Organiza tareas y pendientes de estudio.", color = Color.Gray)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = add,
                    onValueChange = { add = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nueva tarea") }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (add.isNotBlank()) {
                        tasks += TaskStore.newTask(add)
                        TaskStore.save(this@MainActivity, tasks)
                        add = ""
                    }
                }) { Text("+") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(tasks, key = { it.id }) { task ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(task.done, onCheckedChange = { checked ->
                            val index = tasks.indexOfFirst { it.id == task.id }
                            if (index >= 0) {
                                tasks[index] = task.copy(done = checked)
                                TaskStore.save(this@MainActivity, tasks)
                            }
                        })
                        Text(task.title, color = if (task.done) Color.Gray else Color.White, modifier = Modifier.weight(1f))
                        Text("✕", color = Color.Gray, modifier = Modifier.clickable {
                            val index = tasks.indexOfFirst { it.id == task.id }
                            if (index >= 0) tasks.removeAt(index)
                            TaskStore.save(this@MainActivity, tasks)
                        }.padding(10.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun Composer() {
        selectedImageName?.let {
            Text(
                "🖼️ $it  ✕",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp).clickable {
                    selectedImageUri = null
                    selectedImageName = null
                }
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp, 10.dp),
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
                Text(if (listening) "••" else "🎤")
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

    @Composable
    private fun Bubble(message: UiMessage) {
        val user = message.role == "user"
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(if (user) .86f else .94f),
                color = if (user) Color(0xFF263238) else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(14.dp, 11.dp)) {
                    if (!user) Text("J.A.V.I.", color = Color(0xFF72E3D1), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(message.content, color = Color(0xFFF2F3F5), fontSize = 15.sp, lineHeight = 21.sp)
                    message.imageBase64?.let { data ->
                        decodeBitmap(data)?.let { bitmap ->
                            Spacer(Modifier.height(10.dp))
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = "Imagen",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }
    }

    private fun decodeBitmap(data: String) = runCatching {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun resolveName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "Imagen"
        } ?: "Imagen"
    } catch (_: Exception) {
        "Imagen"
    }

    private fun isImageAction(text: String, hasImage: Boolean): Boolean {
        val normalized = text.lowercase()
        return if (hasImage) {
            listOf("edita", "editar", "cambia", "quita", "elimina", "agrega", "añade", "transforma", "convierte", "mejora")
                .any { normalized.contains(it) }
        } else {
            listOf("crea una imagen", "genera una imagen", "haz una imagen", "dibuja", "diseña una imagen")
                .any { normalized.contains(it) }
        }
    }

    private fun generateStudioImage() {
        val prompt = textInput.trim()
        if (prompt.isBlank()) return
        textInput = ""
        thinking = true
        status = "Generando imagen…"
        lifecycleScope.launch {
            try {
                val result = ApiClient.generateImage(prompt, selectedImageUri)
                studioImageBase64 = result.base64
                status = "Listo"
            } catch (e: Exception) {
                status = e.message ?: "No pude generar la imagen"
            } finally {
                selectedImageUri = null
                selectedImageName = null
                thinking = false
            }
        }
    }

    private fun sendTypedMessage() {
        val text = textInput.trim()
        val image = selectedImageUri
        if (text.isBlank() && image == null) return
        textInput = ""
        selectedImageUri = null
        selectedImageName = null
        sendToJavi(if (text.isBlank()) "¿Qué observas en esta imagen?" else text, image)
    }

    private fun sendToJavi(text: String, image: Uri? = null) {
        messages += UiMessage("user", text)
        saveChat()
        thinking = true
        status = "Pensando…"
        lifecycleScope.launch {
            try {
                if (isImageAction(text, image != null)) {
                    val result = ApiClient.generateImage(text, image)
                    messages += UiMessage("assistant", result.reply, result.base64)
                    speak(result.reply)
                } else {
                    val reply = ApiClient.sendMessage(messages.map { ChatMessage(it.role, it.content) }, image)
                    messages += UiMessage("assistant", reply)
                    speak(reply)
                }
                status = "Listo"
            } catch (e: Exception) {
                messages += UiMessage("assistant", e.message ?: "No pude procesar la solicitud.")
                status = "Error"
            } finally {
                thinking = false
                saveChat()
            }
        }
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun listen() {
        if (listening) return
        listening = true
        status = "Escuchando…"
        try {
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO")
            })
        } catch (_: Exception) {
            listening = false
            status = "No pude iniciar el micrófono"
        }
    }

    private fun speak(text: String) {
        if (voiceEnabled) runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "javi-${System.currentTimeMillis()}")
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        status = "Listo"
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { sendToJavi(it) }
    }

    override fun onError(error: Int) { listening = false; status = "Listo" }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        saveChat()
        runCatching { recognizer.destroy() }
        runCatching { tts.shutdown() }
        super.onDestroy()
    }
}
