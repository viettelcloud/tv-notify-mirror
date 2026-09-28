package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cast_notify_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
        private const val KEY_PRIVACY_MODE = "key_privacy_mode"
        private const val KEY_HIDE_OTP = "key_hide_otp"
        private const val KEY_TV_IP = "key_tv_ip"
        private const val KEY_TV_PORT = "key_tv_port"
        private const val KEY_TV_NAME = "key_tv_name"
        private const val KEY_ALLOWED_PACKAGES = "key_allowed_packages"
        private const val KEY_SOUND_ENABLED = "key_sound_enabled"
        private const val KEY_POPUP_DURATION = "key_popup_duration"

        // Default popular apps enabled out of the box if nothing selected
        val DEFAULT_ALLOWED_PRESETS = setOf(
            "com.zing.zalo",          // Zalo
            "com.facebook.orca",       // Messenger
            "com.whatsapp",            // WhatsApp
            "org.telegram.messenger",  // Telegram
            "com.google.android.talk", // Google Chat
            "com.google.android.apps.messaging", // SMS
            "com.android.mms",
            "com.google.android.gm",   // Gmail
            "com.discord",             // Discord
            "com.instagram.android"    // Instagram
        )

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context).also { instance = it }
            }
        }
    }

    private val _isServiceEnabled = MutableStateFlow(prefs.getBoolean(KEY_SERVICE_ENABLED, true))
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    private val _isPrivacyMode = MutableStateFlow(prefs.getBoolean(KEY_PRIVACY_MODE, false))
    val isPrivacyMode: StateFlow<Boolean> = _isPrivacyMode.asStateFlow()

    private val _isHideOtp = MutableStateFlow(prefs.getBoolean(KEY_HIDE_OTP, true))
    val isHideOtp: StateFlow<Boolean> = _isHideOtp.asStateFlow()

    private val _tvIp = MutableStateFlow(prefs.getString(KEY_TV_IP, "") ?: "")
    val tvIp: StateFlow<String> = _tvIp.asStateFlow()

    private val _tvPort = MutableStateFlow(prefs.getInt(KEY_TV_PORT, 8080))
    val tvPort: StateFlow<Int> = _tvPort.asStateFlow()

    private val _tvName = MutableStateFlow(prefs.getString(KEY_TV_NAME, "Android TV") ?: "Android TV")
    val tvName: StateFlow<String> = _tvName.asStateFlow()

    private val _allowedPackages = MutableStateFlow(
        prefs.getStringSet(KEY_ALLOWED_PACKAGES, DEFAULT_ALLOWED_PRESETS)?.toSet() ?: DEFAULT_ALLOWED_PRESETS
    )
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    private val _isSoundEnabled = MutableStateFlow(prefs.getBoolean(KEY_SOUND_ENABLED, true))
    val isSoundEnabled: StateFlow<Boolean> = _isSoundEnabled.asStateFlow()

    private val _popupDuration = MutableStateFlow(prefs.getInt(KEY_POPUP_DURATION, 5))
    val popupDuration: StateFlow<Int> = _popupDuration.asStateFlow()

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
        _isServiceEnabled.value = enabled
    }

    fun setPrivacyMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVACY_MODE, enabled).apply()
        _isPrivacyMode.value = enabled
    }

    fun setHideOtp(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_OTP, enabled).apply()
        _isHideOtp.value = enabled
    }

    fun setTvConnection(ip: String, port: Int = 8080, name: String = "Android TV") {
        prefs.edit()
            .putString(KEY_TV_IP, ip)
            .putInt(KEY_TV_PORT, port)
            .putString(KEY_TV_NAME, name)
            .apply()
        _tvIp.value = ip
        _tvPort.value = port
        _tvName.value = name
    }

    fun toggleAppAllowed(packageName: String, allowed: Boolean) {
        val current = _allowedPackages.value.toMutableSet()
        if (allowed) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, current).apply()
        _allowedPackages.value = current
    }

    fun setAllAppsAllowed(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, packages).apply()
        _allowedPackages.value = packages
    }

    fun isAppAllowed(packageName: String): Boolean {
        val allowed = _allowedPackages.value
        // If empty, default to checking if it's in default presets or allow
        return allowed.contains(packageName)
    }

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
        _isSoundEnabled.value = enabled
    }

    fun setPopupDuration(seconds: Int) {
        prefs.edit().putInt(KEY_POPUP_DURATION, seconds).apply()
        _popupDuration.value = seconds
    }
}
