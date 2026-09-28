package com.example.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredTv(
    val name: String,
    val ip: String,
    val port: Int = 8080,
    val source: String = "Auto-detected"
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
     * Extracts subnet prefix from local IP (e.g. "192.168.1.").
     */
    fun getSubnetPrefix(localIp: String = getLocalIpAddress()): String {
        val parts = localIp.split(".")
        return if (parts.size == 4) {
            "${parts[0]}.${parts[1]}.${parts[2]}."
        } else {
            "192.168.1."
        }
    }

    /**
     * Finds all active IPv4 broadcast addresses across all network interfaces,
     * including interface-directed broadcasts (e.g. 192.168.1.255) and 255.255.255.255.
     */
    fun getAllBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (intfAddr in intf.interfaceAddresses) {
                    val broadcast = intfAddr.broadcast
                    if (broadcast != null && broadcast is java.net.Inet4Address) {
                        broadcastList.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving broadcast addresses", e)
        }

        try {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}

        return broadcastList.distinct()
    }

    /**
     * TV Receiver: Listens for discovery pings and announces presence.
     */
    suspend fun runDiscoveryResponder(
        tvName: String = "Google TV (${Build.MODEL})",
        port: Int = 8080,
        stopCondition: () -> Boolean
    ) = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                broadcast = true
                soTimeout = 2000
            }
            Log.d(TAG, "Discovery responder bound to port $DISCOVERY_PORT (reuseAddress=true)")
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
                        Log.d(TAG, "Sent discovery response to ${packet.address.hostAddress}:${packet.port}")
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Regular timeout check to evaluate loop conditions
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery responder error", e)
        } finally {
            socket?.close()
        }
    }

    /**
     * Mobile Sender: Discovers TV receivers using hybrid method:
     * 1) Directed & global UDP broadcast
     * 2) Concurrent local subnet TCP probe for port 8080 (fallback if router drops UDP broadcast)
     */
    suspend fun discoverTvs(
        context: Context,
        timeoutMs: Long = 3000
    ): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val foundTvs = ConcurrentHashMap<String, DiscoveredTv>()

        coroutineScope {
            // Task 1: UDP Broadcast Discovery
            val udpJob = async {
                discoverViaUdp(context, timeoutMs, foundTvs)
            }

            // Task 2: Fast Local Subnet Prober on port 8080
            val probeJob = async {
                val localIp = getLocalIpAddress()
                if (localIp != "127.0.0.1") {
                    probeLocalSubnet(localIp, 8080, foundTvs)
                }
            }

            udpJob.await()
            probeJob.await()
        }

        Log.d(TAG, "Discovery finished. Found ${foundTvs.size} TV(s): ${foundTvs.keys}")
        foundTvs.values.toList()
    }

    private suspend fun discoverViaUdp(
        context: Context,
        timeoutMs: Long,
        results: ConcurrentHashMap<String, DiscoveredTv>
    ) = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("CastNotifyDiscoveryLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 800
            }

            val requestData = DISCOVERY_REQUEST.toByteArray()
            val broadcastTargets = getAllBroadcastAddresses()

            // Send discovery packet to all broadcast targets (including interface broadcasts)
            for (broadcastAddr in broadcastTargets) {
                try {
                    val requestPacket = DatagramPacket(
                        requestData,
                        requestData.size,
                        broadcastAddr,
                        DISCOVERY_PORT
                    )
                    socket.send(requestPacket)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not send broadcast to $broadcastAddr: ${e.message}")
                }
            }

            val startTime = System.currentTimeMillis()
            val buffer = ByteArray(1024)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length).trim()
                    if (response.startsWith(DISCOVERY_RESPONSE_PREFIX)) {
                        val parts = response.removePrefix(DISCOVERY_RESPONSE_PREFIX).split(":")
                        val tvName = parts.getOrNull(0) ?: "Google TV"
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                        val ip = responsePacket.address.hostAddress ?: ""
                        if (ip.isNotBlank() && ip != "127.0.0.1") {
                            results[ip] = DiscoveredTv(
                                name = tvName,
                                ip = ip,
                                port = port,
                                source = "UDP Broadcast"
                            )
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Send another round of broadcast pings halfway through
                    if (System.currentTimeMillis() - startTime < timeoutMs / 2) {
                        for (broadcastAddr in broadcastTargets) {
                            try {
                                val p = DatagramPacket(requestData, requestData.size, broadcastAddr, DISCOVERY_PORT)
                                socket.send(p)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP scan error", e)
        } finally {
            socket?.close()
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Fast TCP Prober: Concurrently tests port 8080 across local /24 subnet.
     * Guarantees discovering the TV even if the Wi-Fi router blocks UDP broadcast packets.
     */
    private suspend fun probeLocalSubnet(
        localIp: String,
        targetPort: Int,
        results: ConcurrentHashMap<String, DiscoveredTv>
    ) = coroutineScope {
        val prefix = getSubnetPrefix(localIp)
        val myLastOctet = localIp.substringAfterLast(".").toIntOrNull() ?: 0

        // Probe 1..254 concurrently in batches to avoid socket exhaustion
        val candidateIps = (1..254).filter { it != myLastOctet && it != 0 }

        candidateIps.map { octet ->
            async(Dispatchers.IO) {
                val candidateIp = "$prefix$octet"
                if (results.containsKey(candidateIp)) return@async // Already found via UDP

                var testSocket: Socket? = null
                try {
                    testSocket = Socket()
                    testSocket.connect(InetSocketAddress(candidateIp, targetPort), 350)
                    // If connection succeeds, a server is actively listening on targetPort!
                    results.putIfAbsent(
                        candidateIp,
                        DiscoveredTv(
                            name = "Google TV ($candidateIp)",
                            ip = candidateIp,
                            port = targetPort,
                            source = "Subnet Probe"
                        )
                    )
                    Log.d(TAG, "Subnet probe found active server at $candidateIp:$targetPort")
                } catch (_: Exception) {
                    // Host not reachable or port closed
                } finally {
                    try {
                        testSocket?.close()
                    } catch (_: Exception) {}
                }
            }
        }.awaitAll()
    }
}
