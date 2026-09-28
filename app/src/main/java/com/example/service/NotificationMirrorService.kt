package com.example.service

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.example.data.PreferencesManager
import com.example.model.NotificationPayload
import com.example.network.ConnectionStatus
import com.example.network.PhoneWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

data class MirroredNotificationLog(
    val id: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    val status: String // "Mirrored", "Blocked by filter", "Service disabled", "Failed"
)

class NotificationMirrorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifMirrorService"

        private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var webSocketClient: PhoneWebSocketClient? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _mirrorLogs = MutableStateFlow<List<MirroredNotificationLog>>(emptyList())
        val mirrorLogs: StateFlow<List<MirroredNotificationLog>> = _mirrorLogs.asStateFlow()

        fun initClient(scope: CoroutineScope): PhoneWebSocketClient {
            return (webSocketClient ?: PhoneWebSocketClient(scope).also { webSocketClient = it })
        }

        fun sendTestPayload(context: Context, customApp: String = "Zalo", customTitle: String = "John Doe", customMessage: String = "Hello, see you on TV!"): Boolean {
            val prefs = PreferencesManager.getInstance(context)
            val client = webSocketClient ?: PhoneWebSocketClient(serviceScope).also { webSocketClient = it }
            
            // Connect if not connected
            if (client.status.value != ConnectionStatus.CONNECTED && prefs.tvIp.value.isNotBlank()) {
                client.connect(prefs.tvIp.value, prefs.tvPort.value)
            }

            val payload = NotificationPayload(
                appName = customApp,
                title = customTitle,
                message = customMessage,
                timestamp = System.currentTimeMillis(),
                privacyMode = prefs.isPrivacyMode.value,
                packageName = "com.sample.$customApp"
            )

            val success = client.sendPayload(payload)
            addLog(
                MirroredNotificationLog(
                    id = payload.id,
                    appName = payload.appName,
                    packageName = payload.packageName,
                    title = payload.title,
                    message = payload.message,
                    timestamp = payload.timestamp,
                    status = if (success) "Sent to TV" else "Queued / TV Offline"
                )
            )
            return success
        }

        fun addLog(log: MirroredNotificationLog) {
            val updated = listOf(log) + _mirrorLogs.value.take(49)
            _mirrorLogs.value = updated
        }

        fun clearLogs() {
            _mirrorLogs.value = emptyList()
        }
    }

    private lateinit var prefs: PreferencesManager
    private val recentNotifications = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(applicationContext)
        if (webSocketClient == null) {
            webSocketClient = PhoneWebSocketClient(serviceScope)
        }
        _isServiceConnected.value = true
        Log.d(TAG, "NotificationMirrorService created")

        // Connect if IP is saved
        val savedIp = prefs.tvIp.value
        if (savedIp.isNotBlank() && prefs.isServiceEnabled.value) {
            webSocketClient?.connect(savedIp, prefs.tvPort.value)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isServiceConnected.value = true
        Log.d(TAG, "Notification listener connected to system")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isServiceConnected.value = false
        Log.d(TAG, "Notification listener disconnected from system")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkgName = sbn.packageName ?: return

        // Skip our own package to prevent loopback
        if (pkgName == packageName) return

        // Check master toggle
        if (!prefs.isServiceEnabled.value) {
            return
        }

        // Filter system ongoing/non-clearable spam if appropriate (keep call notifications, etc.)
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ""

        // Skip completely empty notifications
        if (title.isBlank() && text.isBlank()) return

        // Resolve friendly application name
        val appName = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkgName.substringAfterLast('.')
        }

        // De-duplication check: ignore rapid duplicate notifications within 8 seconds
        val duplicateKey = "$pkgName|$title|$text"
        val now = System.currentTimeMillis()
        val lastTime = recentNotifications[duplicateKey]
        if (lastTime != null && (now - lastTime) < 8000L) {
            Log.d(TAG, "Skipping duplicate notification for $duplicateKey")
            return
        }
        recentNotifications[duplicateKey] = now
        if (recentNotifications.size > 100) {
            recentNotifications.entries.removeIf { (now - it.value) > 15000L }
        }

        // Granular package filter check
        val isAllowed = prefs.isAppAllowed(pkgName)
        if (!isAllowed) {
            addLog(
                MirroredNotificationLog(
                    id = "${sbn.id}_${sbn.postTime}",
                    appName = appName,
                    packageName = pkgName,
                    title = title,
                    message = text,
                    timestamp = sbn.postTime,
                    status = "Blocked (Filtered App)"
                )
            )
            return
        }

        // Apply Privacy Filter
        val (processedTitle, processedMessage, wasFiltered) = applyPrivacyFilter(title, text, prefs)

        // Try extracting small app icon thumbnail
        val iconBase64 = try {
            val drawable = packageManager.getApplicationIcon(pkgName)
            drawableToBase64(drawable)
        } catch (e: Exception) {
            null
        }

        val payload = NotificationPayload(
            appName = appName,
            title = processedTitle,
            message = processedMessage,
            timestamp = sbn.postTime,
            privacyMode = wasFiltered,
            packageName = pkgName,
            iconBase64 = iconBase64
        )

        // Transmit to TV
        serviceScope.launch {
            val client = webSocketClient ?: PhoneWebSocketClient(serviceScope).also { webSocketClient = it }
            if (client.status.value != ConnectionStatus.CONNECTED && prefs.tvIp.value.isNotBlank()) {
                client.connect(prefs.tvIp.value, prefs.tvPort.value)
            }

            val sent = client.sendPayload(payload)
            addLog(
                MirroredNotificationLog(
                    id = payload.id,
                    appName = appName,
                    packageName = pkgName,
                    title = processedTitle,
                    message = processedMessage,
                    timestamp = payload.timestamp,
                    status = if (sent) "Mirrored to TV" else "TV Offline"
                )
            )
        }
    }

    private fun applyPrivacyFilter(title: String, message: String, prefs: PreferencesManager): Triple<String, String, Boolean> {
        val isPrivacyMode = prefs.isPrivacyMode.value
        val isHideOtp = prefs.isHideOtp.value

        var newTitle = title
        var newMessage = message
        var filtered = false

        if (isPrivacyMode) {
            // High privacy mode: obscure sensitive contents
            newMessage = "New message received"
            filtered = true
        } else if (isHideOtp) {
            // Obscure OTP patterns: e.g., "code is 123456" -> "code is [HIDDEN OTP]"
            val otpPattern = Pattern.compile("(?i)\\b(\\d{4,8})\\b|(\\b(otp|code|pin|token|verification)\\b[\\s:]*(\\d+))")
            val matcher = otpPattern.matcher(message)
            if (matcher.find()) {
                newMessage = message.replace(Regex("\\b\\d{4,8}\\b"), "••••••")
                filtered = true
            }
        }

        return Triple(newTitle, newMessage, filtered)
    }

    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceIn(32, 96),
                    drawable.intrinsicHeight.coerceIn(32, 96),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 85, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceConnected.value = false
        Log.d(TAG, "NotificationMirrorService destroyed")
    }
}
