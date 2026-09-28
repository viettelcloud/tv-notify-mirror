package com.example.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class DiscoveredTv(
    val name: String,
    val ip: String,
    val port: Int = 8080
)

object NetworkDiscovery {
    private const val TAG = "NetworkDiscovery"
    const val DISCOVERY_PORT = 8888
    private const val DISCOVERY_REQUEST = "CASTNOTIFY_DISCOVER"
    private const val DISCOVERY_RESPONSE_PREFIX = "CASTNOTIFY_TV:"

    /**
     * Resolves the primary IPv4 address of the local device.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
        }
        return "127.0.0.1"
    }

    /**
     * TV Receiver: Listens for discovery pings and announces presence.
     */
    suspend fun runDiscoveryResponder(
        tvName: String = "Android TV (${Build.MODEL})",
        port: Int = 8080,
        stopCondition: () -> Boolean
    ) = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(DISCOVERY_PORT).apply {
                broadcast = true
                soTimeout = 2000
            }
            val buffer = ByteArray(1024)

            while (isActive && !stopCondition()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()
                    if (message == DISCOVERY_REQUEST) {
                        val response = "$DISCOVERY_RESPONSE_PREFIX$tvName:$port"
                        val responseData = response.toByteArray()
                        val responsePacket = DatagramPacket(
                            responseData,
                            responseData.size,
                            packet.address,
                            packet.port
                        )
                        socket.send(responsePacket)
                        Log.d(TAG, "Responded to discovery from ${packet.address.hostAddress}")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Regular timeout check
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery responder error", e)
        } finally {
            socket?.close()
        }
    }

    /**
     * Mobile Sender: Sends broadcast to discover TV receivers on local Wi-Fi.
     */
    suspend fun discoverTvs(
        context: Context,
        timeoutMs: Long = 3000
    ): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val foundTvs = mutableMapOf<String, DiscoveredTv>()
        var socket: DatagramSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null

        try {
            // Acquire multicast lock if available
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("CastNotifyDiscoveryLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1000
            }

            val requestData = DISCOVERY_REQUEST.toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val requestPacket = DatagramPacket(
                requestData,
                requestData.size,
                broadcastAddr,
                DISCOVERY_PORT
            )

            // Send 2 broadcast packets
            socket.send(requestPacket)

            val startTime = System.currentTimeMillis()
            val buffer = ByteArray(1024)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length).trim()
                    if (response.startsWith(DISCOVERY_RESPONSE_PREFIX)) {
                        val parts = response.removePrefix(DISCOVERY_RESPONSE_PREFIX).split(":")
                        val tvName = parts.getOrNull(0) ?: "Android TV"
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                        val ip = responsePacket.address.hostAddress ?: ""
                        if (ip.isNotBlank()) {
                            foundTvs[ip] = DiscoveredTv(name = tvName, ip = ip, port = port)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout slice, send ping again if still searching
                    if (System.currentTimeMillis() - startTime < timeoutMs / 2) {
                        try {
                            socket.send(requestPacket)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery scan error", e)
        } finally {
            socket?.close()
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }

        foundTvs.values.toList()
    }
}
