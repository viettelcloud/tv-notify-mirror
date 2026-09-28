package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.R
import com.example.model.NotificationPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationOverlayService : Service() {

    companion object {
        private const val TAG = "NotifOverlayService"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val payloadJson = intent?.getStringExtra("payload_json")
        if (payloadJson != null) {
            val payload = NotificationPayload.fromJsonString(payloadJson)
            if (payload != null) {
                showOverlay(payload)
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlay(payload: NotificationPayload) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        handler.post {
            try {
                removeExistingOverlay()

                val inflater = LayoutInflater.from(this)
                val view = inflater.inflate(R.layout.overlay_notification_card, null)
                overlayView = view

                // Bind text
                val txtApp = view.findViewById<TextView>(R.id.overlay_app_name)
                val txtTitle = view.findViewById<TextView>(R.id.overlay_title)
                val txtMessage = view.findViewById<TextView>(R.id.overlay_message)
                val txtTime = view.findViewById<TextView>(R.id.overlay_time)

                txtApp?.text = payload.appName
                txtTitle?.text = if (payload.title.isNotBlank()) payload.title else payload.appName
                txtMessage?.text = payload.message
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                txtTime?.text = timeFormat.format(Date(payload.timestamp))

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 36 // Margins from right
                    y = 36 // Margins from top
                }

                windowManager?.addView(view, params)
                Log.d(TAG, "Notification overlay displayed at top-right")

                // Auto-dismiss after 5 seconds
                dismissRunnable?.let { handler.removeCallbacks(it) }
                dismissRunnable = Runnable {
                    removeExistingOverlay()
                    stopSelf()
                }
                handler.postDelayed(dismissRunnable!!, 5000)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to display overlay window", e)
            }
        }
    }

    private fun removeExistingOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
            overlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissRunnable?.let { handler.removeCallbacks(it) }
        removeExistingOverlay()
        Log.d(TAG, "NotificationOverlayService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
