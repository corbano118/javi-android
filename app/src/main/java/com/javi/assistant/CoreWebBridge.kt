package com.javi.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CoreWebBridge {
    private const val CORE_ORIGIN = "https://j-a-v-i-45ursb.v2.appdeploy.ai"
    private const val ANDROID_CHAT_URL = "$CORE_ORIGIN/api/android-chat"
    private const val IMAGE_URL = "$CORE_ORIGIN/api/image"
    @Volatile private var appContext: Context? = null

    data class ImageResult(val reply: String, val base64: String, val mimeType: String)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun sendMessage(history: List<ChatMessage>, imageUri: Uri? = null): String = withContext(Dispatchers.IO) {
        if (imageUri != null) {
            throw IllegalStateException("El análisis de fotos está migrándose al nuevo transporte. El chat de texto ya puede funcionar normalmente.")
        }
        val contextText = history.takeLast(12).joinToString("\n") { message ->
            if (message.role == "assistant") "J.A.V.I.: ${message.content}" else "Usuario: ${message.content}"
        }
        val body = getJson(ANDROID_CHAT_URL, contextText)
        JSONObject(body).optString("reply").ifBlank { "No obtuve respuesta." }
    }

    suspend fun generateImage(prompt: String, imageUri: Uri? = null): ImageResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("prompt", prompt.trim())
            imageUri?.let { put("image", imageJson(it)) }
        }
        val body = postJson(IMAGE_URL, payload)
        val json = JSONObject(body)
        val image = json.optJSONObject("image") ?: throw IllegalStateException("J.A.V.I. no devolvió una imagen.")
        ImageResult(
            reply = json.optString("reply").ifBlank { "Listo." },
            base64 = image.optString("data"),
            mimeType = image.optString("mimeType").ifBlank { "image/png" }
        )
    }

    private fun imageJson(uri: Uri): JSONObject {
        val context = appContext ?: throw IllegalStateException("J.A.V.I. Core no está inicializado.")
        val mime = context.contentResolver.getType(uri)?.lowercase()
        val allowedMime = when (mime) {
            "image/png", "image/webp", "image/jpeg" -> mime
            else -> "image/jpeg"
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > 6 * 1024 * 1024) throw IllegalArgumentException("La imagen supera el límite de 6 MB.")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("No pude leer la imagen seleccionada.")
        return JSONObject().apply {
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("mimeType", allowedMime)
        }
    }

    private fun getJson(endpoint: String, message: String): String {
        val encoded = URLEncoder.encode(message, "UTF-8")
        val connection = (URL("$endpoint?q=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 90_000
            doInput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JAVI-Android/0.15")
        }
        return readResponse(connection)
    }

    private fun postJson(endpoint: String, payload: JSONObject): String {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        connection.outputStream.use { it.write(bytes) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): String {
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(body).optString("error").ifBlank { JSONObject(body).optString("message") }
                }.getOrDefault("").ifBlank {
                    body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(220)
                }
                throw IllegalStateException("J.A.V.I. Core respondió $code: ${detail.ifBlank { "Error HTTP" }}")
            }
            if (body.isBlank()) throw IllegalStateException("J.A.V.I. Core respondió vacío.")
            body
        } finally {
            connection.disconnect()
        }
    }
}
