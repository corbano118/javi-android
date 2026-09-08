package com.javi.assistant

import android.content.Context
import android.net.Uri

data class ChatMessage(val role: String, val content: String)

object ApiClient {
    fun init(context: Context) = CoreWebBridge.init(context)

    suspend fun sendMessage(history: List<ChatMessage>, imageUri: Uri? = null): String {
        if (history.none { it.role == "user" && it.content.isNotBlank() } && imageUri == null) return "No recibí ningún mensaje."
        return CoreWebBridge.sendMessage(history, imageUri)
    }

    suspend fun generateImage(prompt: String, imageUri: Uri? = null): CoreWebBridge.ImageResult {
        require(prompt.isNotBlank()) { "Escribe qué imagen quieres crear o cómo quieres editarla." }
        return CoreWebBridge.generateImage(prompt, imageUri)
    }
}
