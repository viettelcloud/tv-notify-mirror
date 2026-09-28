package com.example.network

import android.util.Log
import com.example.model.NotificationPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class PhoneWebSocketClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PhoneWebSocketClient"
        private const val RECONNECT_DELAY_MS = 5000L
    }

    private var client: WebSocketClient? = null
    private var currentUri: URI? = null
    private var reconnectJob: Job? = null
    private var shouldKeepConnected: Boolean = false

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    fun connect(ip: String, port: Int = 8080) {
        if (ip.isBlank()) {
            _status.value = ConnectionStatus.DISCONNECTED
            _lastErrorMessage.value = "IP address is blank"
            return
        }

        shouldKeepConnected = true
        val uriStr = if (ip.startsWith("ws://") || ip.startsWith("wss://")) ip else "ws://$ip:$port"
        try {
            val uri = URI(uriStr)
            currentUri = uri
            disconnectInternal(false)
            startClient(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URI: $uriStr", e)
            _status.value = ConnectionStatus.ERROR
            _lastErrorMessage.value = "Invalid URI: ${e.localizedMessage}"
        }
    }

    private fun startClient(uri: URI) {
        _status.value = ConnectionStatus.CONNECTING
        _lastErrorMessage.value = null

        try {
            client = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "Connected to TV: $uri")
                    _status.value = ConnectionStatus.CONNECTED
                    _lastErrorMessage.value = null
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "Received from TV: $message")
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "Connection closed. Code: $code, Reason: $reason, Remote: $remote")
                    _status.value = ConnectionStatus.DISCONNECTED
                    scheduleReconnect()
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket client error", ex)
                    _status.value = ConnectionStatus.ERROR
                    _lastErrorMessage.value = ex?.localizedMessage ?: "Connection error"
                    scheduleReconnect()
                }
            }

            // Timeout in 4 seconds for connect
            client?.setConnectionLostTimeout(15)
            client?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start client", e)
            _status.value = ConnectionStatus.ERROR
            _lastErrorMessage.value = e.localizedMessage
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldKeepConnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(RECONNECT_DELAY_MS)
            if (isActive && shouldKeepConnected && currentUri != null && _status.value != ConnectionStatus.CONNECTED) {
                Log.d(TAG, "Attempting auto-reconnect to $currentUri...")
                currentUri?.let { startClient(it) }
            }
        }
    }

    fun sendPayload(payload: NotificationPayload): Boolean {
        val currentClient = client
        if (currentClient != null && currentClient.isOpen) {
            try {
                currentClient.send(payload.toJsonString())
                Log.d(TAG, "Sent payload: ${payload.title} to TV")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send payload", e)
            }
        }
        return false
    }

    fun disconnect() {
        shouldKeepConnected = false
        reconnectJob?.cancel()
        disconnectInternal(true)
    }

    private fun disconnectInternal(updateState: Boolean) {
        try {
            client?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client", e)
        }
        client = null
        if (updateState) {
            _status.value = ConnectionStatus.DISCONNECTED
        }
    }
}
