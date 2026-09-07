package com.javi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

object ApiClient {
    const val BACKEND_URL = "https://j-a-v-i-45ursb.v2.appdeploy.ai/api/chat"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun sendMessage(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            history.forEach { m -> arr.put(JSONObject().put("role", m.role).put("content", m.content)) }
            val body = JSONObject().put("messages", arr).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(BACKEND_URL).post(body).build()
            client.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@use "J.A.V.I. Core respondió con error ${r.code}."
                JSONObject(raw).optString("reply", "No obtuve respuesta.")
            }
        } catch (e: Exception) {
            "No pude conectar con J.A.V.I. Core: ${e.message ?: "error de red"}."
        }
    }
}
