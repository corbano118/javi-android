package com.javi.assistant

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

object MediaSaver {
    suspend fun saveMusic(context: Context, url: String, displayName: String): Uri = withContext(Dispatchers.IO) {
        val bytes = download(url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/JAVI")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No pude crear el archivo de música")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("No pude escribir la música")
                values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(context.getExternalFilesDir(null), "Music/JAVI").apply { mkdirs() }
            val file = File(dir, displayName); FileOutputStream(file).use { it.write(bytes) }; Uri.fromFile(file)
        }
    }

    suspend fun saveVideo(context: Context, urls: List<String>, displayName: String): Uri = withContext(Dispatchers.IO) {
        require(urls.isNotEmpty()) { "No hay video generado" }
        val cacheDir = File(context.cacheDir, "javi_media_${System.currentTimeMillis()}").apply { mkdirs() }
        val pieces = try {
            urls.mapIndexed { index, url ->
                File(cacheDir, "part_$index.mp4").also { FileOutputStream(it).use { out -> out.write(download(url)) } }
            }
        } catch (e: Exception) {
            cacheDir.deleteRecursively(); throw e
        }
        val joined = File(cacheDir, "joined.mp4")
        try {
            if (pieces.size == 1) pieces[0].copyTo(joined, overwrite = true) else joinVideoOnly(pieces, joined)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JAVI")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val target = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("No pude crear el video")
                try {
                    context.contentResolver.openOutputStream(target)?.use { output -> joined.inputStream().use { it.copyTo(output) } }
                        ?: throw IllegalStateException("No pude escribir el video")
                    values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(target, values, null, null)
                    target
                } catch (e: Exception) {
                    context.contentResolver.delete(target, null, null); throw e
                }
            } else {
                val dir = File(context.getExternalFilesDir(null), "Movies/JAVI").apply { mkdirs() }
                val target = File(dir, displayName); joined.copyTo(target, overwrite = true); Uri.fromFile(target)
            }
            uri
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun joinVideoOnly(inputs: List<File>, output: File) {
        val firstExtractor = MediaExtractor().apply { setDataSource(inputs.first().absolutePath) }
        val videoTrack = (0 until firstExtractor.trackCount).firstOrNull {
            firstExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { firstExtractor.release(); throw IllegalStateException("El archivo generado no contiene video") }
        val format = firstExtractor.getTrackFormat(videoTrack)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(format)
        muxer.start()
        firstExtractor.release()
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var offsetUs = 0L
        try {
            inputs.forEach { file ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: run { extractor.release(); throw IllegalStateException("Un segmento no contiene video") }
                val current = extractor.getTrackFormat(track)
                ensureCompatible(format, current)
                extractor.selectTrack(track)
                var lastUs = 0L
                var previousUs = -1L
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleUs = extractor.sampleTime.coerceAtLeast(0L)
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = offsetUs + sampleUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outTrack, buffer, info)
                    previousUs = sampleUs
                    lastUs = sampleUs
                    extractor.advance()
                }
                extractor.release()
                offsetUs += if (previousUs >= 0) lastUs + 33_334L else 0L
            }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun ensureCompatible(a: MediaFormat, b: MediaFormat) {
        val mimeA = a.getString(MediaFormat.KEY_MIME)
        val mimeB = b.getString(MediaFormat.KEY_MIME)
        val widthA = runCatching { a.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull()
        val widthB = runCatching { b.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull()
        val heightA = runCatching { a.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
        val heightB = runCatching { b.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
        if (mimeA != mimeB || widthA != widthB || heightA != heightB) {
            throw IllegalStateException("Los segmentos del video no tienen el mismo formato")
        }
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20_000; readTimeout = 180_000; instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("No pude descargar el archivo generado")
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }
}
