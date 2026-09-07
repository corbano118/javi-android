package com.javi.assistant

data class ChatMessage(val role: String, val content: String)

object ApiClient {
    fun init(context: android.content.Context) {
        CoreWebBridge.init(context)
    }

    suspend fun sendMessage(history: List<ChatMessage>): String {
        val lastUserMessage = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (lastUserMessage.isBlank()) return "No recibí ningún mensaje."
        return CoreWebBridge.sendMessage(lastUserMessage)
    }
}
