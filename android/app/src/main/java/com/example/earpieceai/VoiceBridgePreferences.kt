package com.example.earpieceai

import android.content.Context

object VoiceBridgePreferences {
    private const val PREFS_NAME = "voice_bridge_settings"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    private const val DEFAULT_HOST = "192.168.50.51"
    private const val DEFAULT_PORT = 9090
    private const val DEFAULT_WHISPER_PORT = 5001

    fun getHost(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOST, DEFAULT_HOST)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_HOST
    }

    fun getPort(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)
            .takeIf { it in 1..65535 }
            ?: DEFAULT_PORT
    }

    fun save(context: Context, host: String, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun getBaseUrl(context: Context): String {
        val host = getHost(context)
        val port = getPort(context)
        return "http://$host:$port"
    }

    fun getDisplayValue(context: Context): String {
        return "${getHost(context)}:${getPort(context)}"
    }

    fun getWhisperBaseUrl(context: Context): String {
        return "http://${getHost(context)}:$DEFAULT_WHISPER_PORT"
    }
}
