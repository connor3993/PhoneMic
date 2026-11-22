package com.phonemic.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.phonemic.app.model.ServerDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryClient(private val context: Context) {
    private val tag = "DiscoveryClient"
    private val discoveryPort = 9001

    private val _discoveredDevices = MutableStateFlow<List<ServerDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ServerDevice>> = _discoveredDevices.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startDiscovery() {
        stopDiscovery()
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("PhoneMicMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not acquire multicast lock: ${e.message}")
        }

        s.launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(discoveryPort))
                }
            } catch (e: Exception) {
                try {
                    // Fallback to random local port if 9001 is busy
                    socket = DatagramSocket().apply {
                        broadcast = true
                    }
                } catch (ex: Exception) {
                    Log.e(tag, "Failed to create discovery socket: ${ex.message}")
                    return@launch
                }
            }

            // Launch listener
            launch { listenLoop() }

            // Periodically send ping broadcast
            launch {
                while (isActive) {
                    sendPing()
                    delay(3000)
                }
            }

            // Periodic cleanup of stale devices (> 8s old)
            launch {
                while (isActive) {
                    delay(4000)
                    val now = System.currentTimeMillis()
                    _discoveredDevices.value = _discoveredDevices.value.filter { now - it.lastSeen < 8000 }
                }
            }
        }
    }

    private fun sendPing() {
        try {
            val s = socket ?: return
            val msg = JSONObject().apply {
                put("type", "PHONEMIC_DISCOVERY_PING")
                put("client", "Android PhoneMic")
            }.toString().toByteArray(Charsets.UTF_8)

            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(msg, msg.size, broadcastAddr, discoveryPort)
            s.send(packet)
        } catch (e: Exception) {
            Log.w(tag, "Failed to send discovery ping: ${e.message}")
        }
    }

    private fun listenLoop() {
        val s = socket ?: return
        val buffer = ByteArray(2048)
        while (scope?.isActive == true) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseMessage(jsonStr, packet.address.hostAddress ?: "")
            } catch (e: Exception) {
                if (scope?.isActive == false) break
            }
        }
    }

    private fun parseMessage(jsonStr: String, remoteHost: String) {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type", "")
            if (type == "PHONEMIC_BEACON" || type == "PHONEMIC_DISCOVERY_PONG") {
                val ip = json.optString("ip", remoteHost).ifBlank { remoteHost }
                val port = json.optInt("port", 9000)
                val name = json.optString("name", "PC ($ip)")
                val rate = json.optInt("rate", 48000)
                val channels = json.optInt("channels", 1)

                val device = ServerDevice(
                    name = name,
                    ip = ip,
                    port = port,
                    rate = rate,
                    channels = channels,
                    lastSeen = System.currentTimeMillis()
                )

                val current = _discoveredDevices.value.toMutableList()
                val existingIdx = current.indexOfFirst { it.ip == device.ip && it.port == device.port }
                if (existingIdx >= 0) {
                    current[existingIdx] = device
                } else {
                    current.add(device)
                }
                _discoveredDevices.value = current
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing discovery message: ${e.message}")
        }
    }

    fun stopDiscovery() {
        scope?.cancel()
        scope = null
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing discovery socket: ${e.message}")
        } finally {
            socket = null
        }
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(tag, "Error releasing multicast lock: ${e.message}")
        } finally {
            multicastLock = null
        }
    }
}
