package com.example.model

import org.json.JSONObject

data class NotificationPayload(
    val appName: String,
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val privacyMode: Boolean = false,
    val packageName: String = "",
    val iconBase64: String? = null,
    val id: String = "${System.currentTimeMillis()}_${(1000..9999).random()}"
) {
    fun toJsonString(): String {
        return try {
            val json = JSONObject()
            json.put("appName", appName)
            json.put("title", title)
            json.put("message", message)
            json.put("timestamp", timestamp)
            json.put("privacyMode", privacyMode)
            json.put("packageName", packageName)
            if (iconBase64 != null) {
                json.put("iconBase64", iconBase64)
            }
            json.put("id", id)
            json.toString()
        } catch (_: Throwable) {
            // Pure Kotlin fallback if running in environments without org.json
            buildString {
                append("{")
                append("\"appName\":\"").append(escape(appName)).append("\",")
                append("\"title\":\"").append(escape(title)).append("\",")
                append("\"message\":\"").append(escape(message)).append("\",")
                append("\"timestamp\":").append(timestamp).append(",")
                append("\"privacyMode\":").append(privacyMode).append(",")
                append("\"packageName\":\"").append(escape(packageName)).append("\",")
                if (iconBase64 != null) {
                    append("\"iconBase64\":\"").append(escape(iconBase64)).append("\",")
                }
                append("\"id\":\"").append(escape(id)).append("\"")
                append("}")
            }
        }
    }

    private fun escape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        fun fromJsonString(jsonStr: String): NotificationPayload? {
            // Try standard Android JSONObject first
            try {
                val json = JSONObject(jsonStr)
                return NotificationPayload(
                    appName = json.optString("appName", "Notification"),
                    title = json.optString("title", ""),
                    message = json.optString("message", ""),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    privacyMode = json.optBoolean("privacyMode", false),
                    packageName = json.optString("packageName", ""),
                    iconBase64 = if (json.has("iconBase64")) json.getString("iconBase64") else null,
                    id = json.optString("id", "${System.currentTimeMillis()}")
                )
            } catch (_: Throwable) {
                // Fallback pure Kotlin parser for JVM test environments
                return parseSimpleJson(jsonStr)
            }
        }

        private fun parseSimpleJson(jsonStr: String): NotificationPayload? {
            return try {
                fun extractString(key: String): String {
                    val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    return pattern.find(jsonStr)?.groupValues?.get(1)
                        ?.replace("\\\"", "\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\\", "\\") ?: ""
                }

                fun extractLong(key: String, default: Long): Long {
                    val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
                    return pattern.find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: default
                }

                fun extractBoolean(key: String, default: Boolean): Boolean {
                    val pattern = Regex("\"$key\"\\s*:\\s*(true|false)")
                    return pattern.find(jsonStr)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: default
                }

                val appName = extractString("appName").ifEmpty { "Notification" }
                val title = extractString("title")
                val message = extractString("message")
                val timestamp = extractLong("timestamp", System.currentTimeMillis())
                val privacyMode = extractBoolean("privacyMode", false)
                val packageName = extractString("packageName")
                val iconBase64 = extractString("iconBase64").ifEmpty { null }
                val id = extractString("id").ifEmpty { "${System.currentTimeMillis()}" }

                NotificationPayload(
                    appName = appName,
                    title = title,
                    message = message,
                    timestamp = timestamp,
                    privacyMode = privacyMode,
                    packageName = packageName,
                    iconBase64 = iconBase64,
                    id = id
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
