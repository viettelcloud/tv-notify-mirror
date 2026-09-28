package com.example.network

import android.util.Log
import com.example.model.NotificationPayload
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class TvWebSocketServer(
    port: Int = 8080,
    private val onNotificationReceived: (NotificationPayload) -> Unit,
    private val onClientCountChanged: (Int) -> Unit,
    private val onErrorLogged: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "TvWebSocketServer"
    }

    init {
        isReuseAddr = true
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        val clientIp = conn?.remoteSocketAddress?.address?.hostAddress ?: "Unknown"
        Log.d(TAG, "Client connected: $clientIp")
        onClientCountChanged(connections.size)
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected. Code: $code, reason: $reason")
        onClientCountChanged(connections.size)
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        if (message.isNullOrBlank()) return
        Log.d(TAG, "Message received: $message")
        try {
            val payload = NotificationPayload.fromJsonString(message)
            if (payload != null) {
                onNotificationReceived(payload)
                // Acknowledge receipt
                conn?.send("""{"status":"ack","id":"${payload.id}"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
            onErrorLogged("Parse error: ${e.localizedMessage}")
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // Not used for binary payloads
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket server error", ex)
        onErrorLogged(ex?.localizedMessage ?: "Unknown error")
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket Server started on port $port")
        onClientCountChanged(connections.size)
    }
}
