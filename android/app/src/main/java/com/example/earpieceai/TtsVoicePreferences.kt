package com.example.earpieceai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object TtsVoicePreferences {
    private const val PREFS_NAME = "earpieceai_tts_settings"
    private const val KEY_SELECTED_VOICE = "selected_voice_name"

    data class VoiceOption(
        val voiceName: String?,
        val label: String
    )

    fun getSelectedVoiceName(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_VOICE, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun saveSelectedVoiceName(context: Context, voiceName: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_VOICE, voiceName)
            .apply()
    }

    fun buildVoiceOptions(engine: TextToSpeech): List<VoiceOption> {
        val currentLocale = engine.voice?.locale ?: Locale.getDefault()
        val voices = engine.voices
            ?.filter { voice -> isUsableVoice(voice, currentLocale.language) }
            ?.sortedWith(
                compareBy<Voice> { it.locale.displayName.lowercase(Locale.getDefault()) }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
            )
            .orEmpty()

        val options = mutableListOf(
            VoiceOption(
                voiceName = null,
                label = "System default (${describeVoice(engine.defaultVoice ?: engine.voice)})"
            )
        )
        options += voices.map { voice ->
            VoiceOption(voiceName = voice.name, label = describeVoice(voice))
        }
        return options.distinctBy { it.voiceName ?: "__default__" }
    }

    fun applySavedVoice(context: Context, engine: TextToSpeech): String {
        val savedVoiceName = getSelectedVoiceName(context)
        if (savedVoiceName.isNullOrBlank()) {
            return describeVoice(engine.voice ?: engine.defaultVoice)
        }
        val matchingVoice = engine.voices?.firstOrNull {
            it.name == savedVoiceName && isUsableVoice(it, it.locale?.language)
        }
        if (matchingVoice != null) {
            engine.voice = matchingVoice
            if (engine.voice?.name == matchingVoice.name) {
                return describeVoice(matchingVoice)
            }
        }
        saveSelectedVoiceName(context, null)
        return describeVoice(engine.voice ?: engine.defaultVoice)
    }

    fun getSavedVoiceLabel(context: Context, engine: TextToSpeech?): String {
        val savedVoiceName = getSelectedVoiceName(context)
        if (savedVoiceName.isNullOrBlank()) {
            return "System default"
        }
        val matchingVoice = engine?.voices?.firstOrNull { it.name == savedVoiceName }
        return if (matchingVoice != null) describeVoice(matchingVoice) else "Saved voice unavailable"
    }

    fun describeVoice(voice: Voice?): String {
        if (voice == null) {
            return "Unknown voice"
        }
        val localeLabel = voice.locale?.displayName?.takeIf { it.isNotBlank() } ?: "Default locale"
        val nameLabel = voice.name.substringAfterLast('-').replace('_', ' ')
        return "$localeLabel - $nameLabel"
    }

    private fun isUsableVoice(voice: Voice, language: String?): Boolean {
        val locale = voice.locale ?: return false
        if (language != null && locale.language != language) {
            return false
        }
        if (voice.isNetworkConnectionRequired) {
            return false
        }
        val features = voice.features.orEmpty().map { it.lowercase(Locale.getDefault()) }.toSet()
        if ("notinstalled" in features || "not_installed" in features || "requiresnetwork" in features) {
            return false
        }
        return true
    }
}
