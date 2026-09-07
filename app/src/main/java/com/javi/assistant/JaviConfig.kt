package com.javi.assistant

import android.content.Context

object JaviConfig {
    private const val PREFS = "javi_config"
    private const val PC_MAC = "pc_mac"
    private const val PC_BROADCAST = "pc_broadcast"
    private const val WAKE_ENABLED = "wake_enabled"
    private const val VOICE_ENABLED = "voice_enabled"

    fun pcMac(context: Context): String = prefs(context).getString(PC_MAC, "") ?: ""
    fun pcBroadcast(context: Context): String = prefs(context).getString(PC_BROADCAST, "255.255.255.255") ?: "255.255.255.255"
    fun wakeEnabled(context: Context): Boolean = prefs(context).getBoolean(WAKE_ENABLED, false)
    fun voiceEnabled(context: Context): Boolean = prefs(context).getBoolean(VOICE_ENABLED, false)

    fun savePc(context: Context, mac: String, broadcast: String) {
        prefs(context).edit()
            .putString(PC_MAC, mac.trim())
            .putString(PC_BROADCAST, broadcast.trim().ifBlank { "255.255.255.255" })
            .apply()
    }

    fun setWakeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(WAKE_ENABLED, enabled).apply()
    }

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(VOICE_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
