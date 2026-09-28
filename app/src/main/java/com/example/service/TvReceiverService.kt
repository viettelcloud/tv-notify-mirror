package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.PreferencesManager
import com.example.model.NotificationPayload
import com.example.network.NetworkDiscovery
import com.example.network.TvWebSocketServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TvReceiverService : Service() {

    companion object {
        private const val TAG = "TvReceiverService"
        private const val CHANNEL_ID = "tv_receiver_channel"
        private const val NOTIF_ID = 2024

        const val ACTION_START = "ACTION_START_TV_SERVER"
        const val ACTION_STOP = "ACTION_STOP_TV_SERVER"
        const val ACTION_TRIGGER_TEST = "ACTION_TRIGGER_TEST_OVERLAY"

        private val _isServerRunning = MutableStateFlow(false)
        val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

        private val _connectedClientsCount = MutableStateFlow(0)
        val connectedClientsCount: StateFlow<Int> = _connectedClientsCount.asStateFlow()

        private val _notificationHistory = MutableStateFlow<List<NotificationPayload>>(emptyList())
        val notificationHistory: StateFlow<List<NotificationPayload>> = _notificationHistory.asStateFlow()

        private val _activePopup = MutableStateFlow<NotificationPayload?>(null)
        val activePopup: StateFlow<NotificationPayload?> = _activePopup.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun clearHistory() {
            _notificationHistory.value = emptyList()
        }

        fun dismissActivePopup() {
            _activePopup.value = null
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketServer: TvWebSocketServer? = null
    private var discoveryJob: Job? = null
    private var autoDismissJob: Job? = null
    private var toneGen: ToneGenerator? = null
    private lateinit var prefs: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(applicationContext)
        createNotificationChannel()
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator init failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TRIGGER_TEST -> {
                val testPayload = NotificationPayload(
                    appName = "Zalo",
                    title = "John Doe",
                    message = "Hello, see you on TV!",
                    timestamp = System.currentTimeMillis(),
                    privacyMode = false,
                    packageName = "com.zing.zalo"
                )
                handleIncomingPayload(testPayload)
            }
            else -> {
                startForeground(NOTIF_ID, buildForegroundNotification())
                startServer()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (webSocketServer != null) return

        val port = prefs.tvPort.value
        serviceScope.launch {
            try {
                webSocketServer = TvWebSocketServer(
                    port = port,
                    onNotificationReceived = { payload ->
                        handleIncomingPayload(payload)
                    },
                    onClientCountChanged = { count ->
                        _connectedClientsCount.value = count
                    },
                    onErrorLogged = { error ->
                        _lastError.value = error
                    }
                )
                webSocketServer?.start()
                _isServerRunning.value = true
                Log.d(TAG, "TvWebSocketServer listening on port $port")

                // Start UDP discovery responder
                discoveryJob?.cancel()
                discoveryJob = launch {
                    NetworkDiscovery.runDiscoveryResponder(
                        tvName = prefs.tvName.value,
                        port = port,
                        stopCondition = { !_isServerRunning.value }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TV server", e)
                _isServerRunning.value = false
                _lastError.value = e.localizedMessage
            }
        }
    }

    private fun handleIncomingPayload(payload: NotificationPayload) {
        // Add to history
        val updatedHistory = listOf(payload) + _notificationHistory.value.take(49)
        _notificationHistory.value = updatedHistory

        // Set active popup
        _activePopup.value = payload

        // Play subtle sound chime if enabled
        if (prefs.isSoundEnabled.value) {
            try {
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            } catch (e: Exception) {
                Log.e(TAG, "Could not play tone", e)
            }
        }

        // Trigger system overlay if permission is granted
        if (Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(this, NotificationOverlayService::class.java).apply {
                putExtra("payload_json", payload.toJsonString())
            }
            startService(overlayIntent)
        }

        // Auto-dismiss popup after 5 seconds (or user configured duration)
        autoDismissJob?.cancel()
        autoDismissJob = serviceScope.launch {
            val durationSec = prefs.popupDuration.value.coerceIn(3, 15)
            delay(durationSec * 1000L)
            if (_activePopup.value?.id == payload.id) {
                _activePopup.value = null
            }
        }
    }

    private fun stopServer() {
        discoveryJob?.cancel()
        autoDismissJob?.cancel()
        try {
            webSocketServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        webSocketServer = null
        _isServerRunning.value = false
        _connectedClientsCount.value = 0
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val localIp = NetworkDiscovery.getLocalIpAddress()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CastNotify TV Receiver Active")
            .setContentText("Listening on $localIp:8080 (Ready for phone notifications)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TV Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows CastNotify background receiver status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        toneGen?.release()
        Log.d(TAG, "TvReceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
