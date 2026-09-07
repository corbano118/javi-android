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
    private const val APP_ORIGIN = "https://j-a-v-i-45ursb.v2.appdeploy.ai"
    private const val BACKEND_URL = "$APP_ORIGIN/api/chat"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            history.forEach { m ->
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }

            val body = JSONObject()
                .put("messages", arr)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url(BACKEND_URL)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Origin", APP_ORIGIN)
                .header("Referer", "$APP_ORIGIN/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36 JAVI-Android/0.6")
                .post(body)
                .build()

            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val detail = try {
                        JSONObject(raw).optString("error").ifBlank { JSONObject(raw).optString("message") }
                    } catch (_: Exception) {
                        raw.take(160)
                    }
                    return@use if (detail.isBlank()) {
                        "J.A.V.I. Core respondió con error ${response.code}."
                    } else {
                        "J.A.V.I. Core respondió con error ${response.code}: $detail"
                    }
                }

                try {
                    JSONObject(raw).optString("reply", "No obtuve respuesta.")
                } catch (_: Exception) {
                    "J.A.V.I. Core envió una respuesta inválida."
                }
            }
        } catch (e: Exception) {
            "No pude conectar con J.A.V.I. Core: ${e.message ?: "error de red"}."
        }
    }
}
