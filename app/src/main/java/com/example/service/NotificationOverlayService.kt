package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.R
import com.example.model.NotificationPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NotificationOverlayService : Service() {

    companion object {
        private const val TAG = "NotifOverlayService"
        private const val NOTIFICATION_LIFETIME_MS = 5000L // 5 seconds display
        private const val MAX_STACK_SIZE = 4
    }

    private var windowManager: WindowManager? = null
    private var stackContainer: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recentOverlays = ConcurrentHashMap<String, Long>()
    private val dismissRunnables = mutableMapOf<View, Runnable>()

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

    @SuppressLint("WakelockTimeout")
    private fun showOverlay(payload: NotificationPayload) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        // De-duplication check: ignore duplicate notification within 8 seconds
        val duplicateKey = "${payload.packageName}|${payload.title}|${payload.message}"
        val now = System.currentTimeMillis()
        val lastSeen = recentOverlays[duplicateKey]
        if (lastSeen != null && (now - lastSeen) < 8000L) {
            Log.d(TAG, "Overlay skipped duplicate notification: $duplicateKey")
            return
        }
        recentOverlays[duplicateKey] = now
        if (recentOverlays.size > 100) {
            recentOverlays.entries.removeIf { (now - it.value) > 15000L }
        }

        // Wake screen if TV screensaver is active/dimmed so notification is clearly visible
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CastNotify:OverlayWake"
            )
            wakeLock?.acquire(3000L) // 3 seconds bright wake
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock error", e)
        }

        handler.post {
            try {
                ensureStackContainer()

                val container = stackContainer ?: return@post

                // Cap maximum stacked items to MAX_STACK_SIZE (remove oldest from top)
                if (container.childCount >= MAX_STACK_SIZE) {
                    val oldest = container.getChildAt(0)
                    removeCardView(oldest)
                }

                val inflater = LayoutInflater.from(this)
                val cardView = inflater.inflate(R.layout.overlay_notification_card, container, false)
                cardView.tag = payload.id

                // Bind content
                val imgIcon = cardView.findViewById<ImageView>(R.id.overlay_app_icon)
                val txtApp = cardView.findViewById<TextView>(R.id.overlay_app_name)
                val txtTitle = cardView.findViewById<TextView>(R.id.overlay_title)
                val txtMessage = cardView.findViewById<TextView>(R.id.overlay_message)
                val txtTime = cardView.findViewById<TextView>(R.id.overlay_time)
                val progressBar = cardView.findViewById<ProgressBar>(R.id.overlay_progress)

                txtApp?.text = payload.appName
                txtTitle?.text = if (payload.title.isNotBlank()) payload.title else payload.appName
                txtMessage?.text = payload.message.ifBlank { "New notification" }
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                txtTime?.text = timeFormat.format(Date(payload.timestamp))

                // Resolve app icon
                if (payload.iconBase64 != null) {
                    try {
                        val decoded = Base64.decode(payload.iconBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                        imgIcon?.setImageBitmap(bitmap)
                    } catch (_: Exception) {}
                } else if (payload.packageName.isNotBlank()) {
                    try {
                        val drawable = packageManager.getApplicationIcon(payload.packageName)
                        imgIcon?.setImageDrawable(drawable)
                    } catch (_: Exception) {}
                }

                // Add card to bottom of the stacked container
                container.addView(cardView)

                // Slide in & fade in animation
                cardView.alpha = 0f
                cardView.translationX = 120f
                cardView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(280)
                    .start()

                // Animate 5-second countdown progress bar
                progressBar?.let { pb ->
                    val animator = ValueAnimator.ofInt(100, 0).apply {
                        duration = NOTIFICATION_LIFETIME_MS
                        interpolator = LinearInterpolator()
                        addUpdateListener { animation ->
                            pb.progress = animation.animatedValue as Int
                        }
                    }
                    animator.start()
                }

                // Schedule auto-dismiss after exactly 5 seconds
                val dismissRunnable = Runnable {
                    removeCardView(cardView)
                }
                dismissRunnables[cardView] = dismissRunnable
                handler.postDelayed(dismissRunnable, NOTIFICATION_LIFETIME_MS)

                Log.d(TAG, "Stacked notification overlay added. Total stack size: ${container.childCount}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to display stacked overlay window", e)
            }
        }
    }

    private fun ensureStackContainer() {
        if (stackContainer == null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.END
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // WindowManager flags for floating over other apps AND screensaver (FLAG_SHOW_WHEN_LOCKED)
            @Suppress("DEPRECATION")
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = 48 // Margins from right (safe overscan)
                y = 48 // Margins from bottom (safe overscan)
            }

            windowManager?.addView(container, params)
            stackContainer = container
            Log.d(TAG, "Created WindowManager stack container at bottom-right with FLAG_SHOW_WHEN_LOCKED")
        }
    }

    private fun removeCardView(cardView: View?) {
        if (cardView == null) return
        val r = dismissRunnables.remove(cardView)
        if (r != null) handler.removeCallbacks(r)

        cardView.animate()
            .alpha(0f)
            .translationX(120f)
            .setDuration(240)
            .withEndAction {
                stackContainer?.removeView(cardView)
                if (stackContainer?.childCount == 0) {
                    removeStackContainer()
                    stopSelf()
                }
            }
            .start()
    }

    private fun removeStackContainer() {
        if (stackContainer != null) {
            try {
                windowManager?.removeView(stackContainer)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing stack container", e)
            }
            stackContainer = null
            Log.d(TAG, "Removed WindowManager stack container (all notifications dismissed)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissRunnables.values.forEach { handler.removeCallbacks(it) }
        dismissRunnables.clear()
        removeStackContainer()
        Log.d(TAG, "NotificationOverlayService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
