package com.phonemic.app.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpAudioSender(
    private val host: String,
    private val port: Int
) {
    private val tag = "UdpAudioSender"
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null

    init {
        try {
            socket = DatagramSocket().apply {
                sendBufferSize = 512 * 1024
            }
            targetAddress = InetAddress.getByName(host)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize UDP socket: ${e.message}")
        }
    }

    fun send(data: ByteArray, length: Int) {
        val s = socket ?: return
        val addr = targetAddress ?: return
        try {
            val packet = DatagramPacket(data, length, addr, port)
            s.send(packet)
        } catch (e: Exception) {
            Log.w(tag, "Error sending UDP packet: ${e.message}")
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing UDP socket: ${e.message}")
        } finally {
            socket = null
        }
    }
}
