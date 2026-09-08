package com.javi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MediaClient {
    private const val ORIGIN = "https://j-a-v-i-45ursb.v2.appdeploy.ai"

    data class GeneratedMedia(val urls: List<String>, val mimeType: String, val durationSeconds: Int)
    data class ImageResult(val reply: String, val base64: String, val mimeType: String)

    suspend fun generateImage(prompt: String): ImageResult = withContext(Dispatchers.IO) {
        val json = getJson("/api/android-image?q=${enc(prompt)}", 120_000)
        val image = json.getJSONObject("image")
        ImageResult(
            reply = json.optString("reply", "Listo."),
            base64 = image.getString("data"),
            mimeType = image.optString("mimeType", "image/png")
        )
    }

    suspend fun generateVideo(prompt: String, durationSeconds: Int, onProgress: (Int) -> Unit): GeneratedMedia {
        val duration = durationSeconds.coerceIn(5, 90)
        val start = withContext(Dispatchers.IO) {
            getJson("/api/android-video/start?q=${enc(prompt)}&duration=$duration", 120_000)
        }
        val ids = start.getJSONArray("ids").let { array -> List(array.length()) { array.getString(it) } }
        val urls = pollRunway(ids, onProgress)
        return GeneratedMedia(urls, "video/mp4", duration)
    }

    suspend fun generateAvatar(
        script: String,
        avatar: String,
        voice: String,
        onProgress: (Int) -> Unit
    ): GeneratedMedia {
        val start = withContext(Dispatchers.IO) {
            getJson(
                "/api/android-avatar/start?q=${enc(script)}&avatar=${enc(avatar)}&voice=${enc(voice)}",
                120_000
            )
        }
        val ids = start.getJSONArray("ids").let { array -> List(array.length()) { array.getString(it) } }
        val urls = pollRunway(ids, onProgress)
        return GeneratedMedia(urls, "video/mp4", 0)
    }

    suspend fun generateMusic(prompt: String, durationSeconds: Int): GeneratedMedia = withContext(Dispatchers.IO) {
        val duration = durationSeconds.coerceIn(3, 240)
        val json = getJson("/api/android-music?q=${enc(prompt)}&duration=$duration", 360_000)
        GeneratedMedia(listOf(json.getString("url")), json.optString("mimeType", "audio/mpeg"), duration)
    }

    private suspend fun pollRunway(ids: List<String>, onProgress: (Int) -> Unit): List<String> {
        repeat(120) {
            val json = withContext(Dispatchers.IO) {
                getJson("/api/android-runway/status?ids=${enc(ids.joinToString(","))}", 120_000)
            }
            val items = json.getJSONArray("items")
            var sum = 0
            val urls = mutableListOf<String>()
            var failed = false
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val state = item.optString("status")
                if (state == "FAILED" || state == "CANCELLED") failed = true
                val p = when (state) {
                    "SUCCEEDED" -> 100
                    else -> (item.optDouble("progress", 0.0) * 100.0).toInt().coerceIn(0, 99)
                }
                sum += p
                item.optString("url").takeIf { it.isNotBlank() }?.let(urls::add)
            }
            onProgress(if (items.length() == 0) 0 else sum / items.length())
            if (failed) throw IllegalStateException("La generación multimedia falló. Inténtalo con otro prompt.")
            if (json.optString("status") == "SUCCEEDED" && urls.size == ids.size) return urls
            delay(5_000)
        }
        throw IllegalStateException("La generación tardó demasiado. Inténtalo nuevamente.")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun getJson(path: String, timeout: Int): JSONObject {
        val connection = (URL(ORIGIN + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = timeout
            doInput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JAVI-Android/0.16")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                    .ifBlank { body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(240) }
                throw IllegalStateException(message.ifBlank { "Error HTTP $code" })
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
