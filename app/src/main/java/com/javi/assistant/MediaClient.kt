package com.javi.assistant

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

object MediaClient {
    data class GeneratedMedia(val urls: List<String>, val mimeType: String, val durationSeconds: Int)
    data class ImageResult(val reply: String, val base64: String, val mimeType: String)

    suspend fun generateImage(prompt: String): ImageResult {
        val json = getJson("/api/android-image?q=${enc(prompt)}", 120_000)
        val image = json.getJSONObject("image")
        return ImageResult(
            reply = json.optString("reply", "Listo."),
            base64 = image.getString("data"),
            mimeType = image.optString("mimeType", "image/png")
        )
    }

    suspend fun generateVideo(prompt: String, durationSeconds: Int, onProgress: (Int) -> Unit): GeneratedMedia {
        val duration = durationSeconds.coerceIn(5, 90)
        val start = getJson("/api/android-video/start?q=${enc(prompt)}&duration=$duration", 120_000)
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
        val start = getJson(
            "/api/android-avatar/start?q=${enc(script)}&avatar=${enc(avatar)}&voice=${enc(voice)}",
            120_000
        )
        val ids = start.getJSONArray("ids").let { array -> List(array.length()) { array.getString(it) } }
        val urls = pollRunway(ids, onProgress)
        return GeneratedMedia(urls, "video/mp4", 0)
    }

    suspend fun generateMusic(prompt: String, durationSeconds: Int): GeneratedMedia {
        val duration = durationSeconds.coerceIn(3, 240)
        val json = getJson("/api/android-music?q=${enc(prompt)}&duration=$duration", 360_000)
        return GeneratedMedia(listOf(json.getString("url")), json.optString("mimeType", "audio/mpeg"), duration)
    }

    private suspend fun pollRunway(ids: List<String>, onProgress: (Int) -> Unit): List<String> {
        repeat(120) {
            val json = getJson("/api/android-runway/status?ids=${enc(ids.joinToString(","))}", 120_000)
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

    private suspend fun getJson(path: String, timeout: Long): JSONObject {
        val body = BrowserPostBridge.getJson(path, timeout)
        if (body.trimStart().startsWith("<")) throw IllegalStateException("J.A.V.I. recibió una página web en lugar de datos. Revisa la conexión.")
        return JSONObject(body)
    }
}
