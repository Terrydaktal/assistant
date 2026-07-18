package com.example.earpieceai

import android.content.Context

object SherpaTtsPreferences {
    private const val PREFS_NAME = "earpieceai_sherpa_tts_settings"
    private const val KEY_SELECTED_VOICE_ID = "selected_voice_id"
    private const val KEY_VOICE_SPEED = "voice_speed"
    const val DEFAULT_VOICE_SPEED = 1.0f
    private const val MIN_VOICE_SPEED = 0.6f
    private const val MAX_VOICE_SPEED = 1.8f

    fun getSelectedVoiceId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_VOICE_ID, SherpaVoiceCatalog.defaultVoice.id)
            ?.takeIf { it.isNotBlank() }
            ?: SherpaVoiceCatalog.defaultVoice.id
    }

    fun saveSelectedVoiceId(context: Context, voiceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_VOICE_ID, voiceId)
            .apply()
    }

    fun getSelectedVoice(context: Context): SherpaVoiceCatalog.VoiceModel {
        return SherpaVoiceCatalog.findById(getSelectedVoiceId(context))
    }

    fun getVoiceSpeed(context: Context): Float {
        val speed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_VOICE_SPEED, DEFAULT_VOICE_SPEED)
        return speed.coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)
    }

    fun saveVoiceSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_VOICE_SPEED, speed.coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED))
            .apply()
    }
}
