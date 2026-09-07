package com.javi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {
    suspend fun send(mac: String, broadcastIp: String = "255.255.255.255", port: Int = 9): Boolean = withContext(Dispatchers.IO) {
        try {
            val macBytes = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray(); require(macBytes.size == 6)
            val bytes = ByteArray(6 + 16 * 6); for (i in 0 until 6) bytes[i] = 0xFF.toByte(); for (i in 6 until bytes.size) bytes[i] = macBytes[(i - 6) % 6]
            DatagramSocket().use { socket -> socket.broadcast = true; socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(broadcastIp), port)) }
            true
        } catch (_: Exception) { false }
    }
}
