package com.example.earpieceai

import android.content.Context

object ImportedAudioPreferences {
    private const val PREFS_NAME = "imported_audio_settings"
    private const val KEY_URI = "selected_uri"
    private const val KEY_DISPLAY_NAME = "selected_display_name"
    private const val KEY_RECORDERS_TREE_URI = "recorders_tree_uri"
    private const val KEY_TAIL_SECONDS = "tail_seconds"
    private const val DEFAULT_TAIL_SECONDS = 30

    fun getSelectedUri(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun getSelectedDisplayName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "No file selected")
            .orEmpty()
    }

    fun getTailSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TAIL_SECONDS, DEFAULT_TAIL_SECONDS)
            .coerceAtLeast(1)
    }

    fun saveSelectedAudio(context: Context, uri: String, displayName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()
    }

    fun getRecordersTreeUri(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDERS_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun saveRecordersTreeUri(context: Context, uri: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDERS_TREE_URI, uri)
            .apply()
    }

    fun clearRecordersTreeUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECORDERS_TREE_URI)
            .apply()
    }

    fun saveTailSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TAIL_SECONDS, seconds.coerceAtLeast(1))
            .apply()
    }
}
