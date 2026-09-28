package com.example.receiver

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.PreferencesManager
import com.example.service.TvReceiverService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CastNotifyBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received boot/startup action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isTelevision = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

            // On TV devices or if TV server was enabled, start TvReceiverService daemon automatically
            val prefs = PreferencesManager.getInstance(context)
            if (isTelevision || prefs.isServiceEnabled.value) {
                Log.d(TAG, "Starting TvReceiverService as background daemon...")
                val serviceIntent = Intent(context, TvReceiverService::class.java).apply {
                    this.action = TvReceiverService.ACTION_START
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start TvReceiverService on boot", e)
                }
            }
        }
    }
}
