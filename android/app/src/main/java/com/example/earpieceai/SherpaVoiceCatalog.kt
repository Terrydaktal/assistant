package com.example.earpieceai

object SherpaVoiceCatalog {
    data class VoiceModel(
        val id: String,
        val label: String,
        val modelDir: String,
        val modelFile: String,
        val dataAssetDir: String,
        val speakerId: Int = 0
    )

    val voices = listOf(
        VoiceModel(
            id = "en_gb_alan_low_int8",
            label = "Piper British English Alan Low Int8",
            modelDir = "tts/vits-piper-en_GB-alan-low-int8",
            modelFile = "en_GB-alan-low.onnx",
            dataAssetDir = "tts/vits-piper-en_GB-alan-low-int8/espeak-ng-data"
        ),
        VoiceModel(
            id = "en_gb_southern_female_low_int8",
            label = "Piper British Southern Female Low Int8",
            modelDir = "tts/vits-piper-en_GB-southern_english_female-low-int8",
            modelFile = "en_GB-southern_english_female-low.onnx",
            dataAssetDir = "tts/vits-piper-en_GB-southern_english_female-low-int8/espeak-ng-data"
        )
    )

    val defaultVoice: VoiceModel = voices.first()

    fun findById(id: String?): VoiceModel {
        return voices.firstOrNull { it.id == id } ?: defaultVoice
    }
}
