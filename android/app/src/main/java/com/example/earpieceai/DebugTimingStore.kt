package com.example.earpieceai

import android.content.Context

object DebugTimingStore {
    private const val PREFS_NAME = "earpieceai_debug_timing"
    private const val KEY_LAST_TIMING = "last_timing"

    fun saveLastTiming(context: Context, timingText: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_TIMING, timingText)
            .apply()
    }

    fun getLastTiming(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_TIMING, "No timing captured yet.")
            .orEmpty()
    }
}
