package com.javi.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object CoreWebBridge {
    @Volatile private var appContext: Context? = null

    data class ImageResult(val reply: String, val base64: String, val mimeType: String)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun sendMessage(history: List<ChatMessage>, imageUri: Uri? = null): String {
        val payload = JSONObject().apply {
            put("messages", JSONArray().apply {
                history.takeLast(20).forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            })
            imageUri?.let { put("image", imageJson(it)) }
        }
        val body = BrowserPostBridge.postJson("/api/chat", payload, 120_000)
        return JSONObject(body).optString("reply").ifBlank { "No obtuve respuesta." }
    }

    suspend fun generateImage(prompt: String, imageUri: Uri? = null): ImageResult {
        val payload = JSONObject().apply {
            put("prompt", prompt.trim())
            imageUri?.let { put("image", imageJson(it)) }
        }
        val body = BrowserPostBridge.postJson("/api/image", payload, 180_000)
        val json = JSONObject(body)
        val image = json.optJSONObject("image") ?: throw IllegalStateException("J.A.V.I. no devolvió una imagen.")
        return ImageResult(
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
}
