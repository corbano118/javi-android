package com.javi.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object CoreWebBridge {
    private const val CHAT_URL = "https://j-a-v-i-45ursb.v2.appdeploy.ai/api/chat"
    private const val IMAGE_URL = "https://j-a-v-i-45ursb.v2.appdeploy.ai/api/image"
    @Volatile private var appContext: Context? = null

    data class ImageResult(val reply: String, val base64: String, val mimeType: String)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun sendMessage(history: List<ChatMessage>, imageUri: Uri? = null): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("messages", JSONArray().apply {
                history.forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            })
            imageUri?.let { put("image", imageJson(it)) }
        }
        val body = postJson(CHAT_URL, payload)
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
                if (total > 6 * 1024 * 1024) {
                    throw IllegalArgumentException("La imagen supera el límite de 6 MB. Elige una imagen más pequeña.")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("No pude leer la imagen seleccionada.")
        return JSONObject().apply {
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("mimeType", allowedMime)
        }
    }

    private fun postJson(endpoint: String, payload: JSONObject): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException(if (detail.isNotBlank()) detail else "Error $code comunicando con J.A.V.I. Core")
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}
