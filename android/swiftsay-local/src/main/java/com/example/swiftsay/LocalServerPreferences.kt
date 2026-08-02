package com.example.swiftsay

import android.content.Context

object LocalServerPreferences {
    private const val PREFS_NAME = "swiftsay_local_server"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    private const val DEFAULT_HOST = "192.168.50.51"
    private const val DEFAULT_PORT = 5001

    fun getHost(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOST, DEFAULT_HOST)
            ?.trim()
            ?.removePrefix("http://")
            ?.removePrefix("https://")
            ?.trimEnd('/')
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
            .putString(KEY_HOST, host.trim().removeSuffix("/"))
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun getBaseUrl(context: Context): String {
        return "http://${getHost(context)}:${getPort(context)}"
    }

    fun getDisplayValue(context: Context): String {
        return "${getHost(context)}:${getPort(context)}"
    }
}
