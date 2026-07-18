package com.example.earpieceai

import android.content.Context

object SpeechEnginePreferences {
    private const val PREFS_NAME = "earpieceai_speech_engine_settings"
    private const val KEY_ENGINE = "selected_engine"

    enum class SpeechEngine(val value: String, val label: String) {
        PIPER("piper", "Piper"),
        GOOGLE("google", "Google TTS");

        companion object {
            fun fromValue(value: String?): SpeechEngine {
                return entries.firstOrNull { it.value == value } ?: PIPER
            }
        }
    }

    fun getSelectedEngine(context: Context): SpeechEngine {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, SpeechEngine.PIPER.value)
        return SpeechEngine.fromValue(value)
    }

    fun saveSelectedEngine(context: Context, engine: SpeechEngine) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENGINE, engine.value)
            .apply()
    }
}
