package com.example.earpieceai

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

class SherpaTtsEngine(private val context: Context) {
    companion object {
        private const val TAG = "SherpaTtsEngine"
        private const val WARMUP_TEXT = "Ready."
        private const val STABLE_NOISE_SCALE = 0.45f
        private const val STABLE_NOISE_W = 0.45f
    }

    data class SynthesisResult(
        val pcm: ShortArray,
        val sampleRate: Int
    )

    private val lock = Any()
    private var tts: OfflineTts? = null
    private var cachedVoiceId: String? = null
    private val assetStagingRoot: File by lazy {
        context.getExternalFilesDir(null) ?: context.filesDir
    }

    fun synthesize(text: String, voice: SherpaVoiceCatalog.VoiceModel, speed: Float): SynthesisResult {
        val engine = getOrCreate(voice)
        val generationConfig = GenerationConfig().apply {
            sid = voice.speakerId
            this.speed = speed
            silenceScale = engine.config.silenceScale
        }
        val audio = engine.generateWithConfig(text, generationConfig)
        return SynthesisResult(
            pcm = audio.toShortPcm(),
            sampleRate = audio.sampleRate
        )
    }

    fun release() {
        synchronized(lock) {
            tts?.release()
            tts = null
            cachedVoiceId = null
        }
    }

    fun preload(voice: SherpaVoiceCatalog.VoiceModel, speed: Float) {
        val startedAt = System.currentTimeMillis()
        val engine = getOrCreate(voice)
        val config = GenerationConfig().apply {
            sid = voice.speakerId
            this.speed = speed
            silenceScale = engine.config.silenceScale
        }
        engine.generateWithConfig(WARMUP_TEXT, config)
        Log.i(TAG, "Preloaded Sherpa voice=${voice.id} speed=$speed in ${System.currentTimeMillis() - startedAt}ms")
    }

    private fun getOrCreate(voice: SherpaVoiceCatalog.VoiceModel): OfflineTts {
        synchronized(lock) {
            val existing = tts
            if (existing != null && cachedVoiceId == voice.id) {
                return existing
            }
            tts?.release()
            val stagedDataDir = stageAssetDirectory(voice.dataAssetDir)
            val config = getOfflineTtsConfig(
                modelDir = voice.modelDir,
                modelName = voice.modelFile,
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = stagedDataDir.absolutePath,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 2,
                isKitten = false,
                isSupertonic = false,
                durationPredictor = "",
                textEncoder = "",
                vectorEstimator = "",
                supertonicVocoder = "",
                ttsJson = "",
                unicodeIndexer = "",
                voiceStyle = ""
            ).also {
                it.model.vits.noiseScale = STABLE_NOISE_SCALE
                it.model.vits.noiseScaleW = STABLE_NOISE_W
            }
            Log.i(
                TAG,
                "Initializing Sherpa voice=${voice.id} modelDir=${voice.modelDir} dataDir=${stagedDataDir.absolutePath}"
            )
            val created = OfflineTts(context.assets, config)
            tts = created
            cachedVoiceId = voice.id
            return created
        }
    }

    private fun stageAssetDirectory(assetDir: String): File {
        val targetDir = File(assetStagingRoot, assetDir)
        copyAssetDirectory(assetDir, targetDir)
        return targetDir
    }

    private fun copyAssetDirectory(assetPath: String, targetDir: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            copyAssetFile(assetPath, targetDir)
            return
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create asset staging directory: ${targetDir.absolutePath}")
        }
        for (child in children) {
            copyAssetDirectory("$assetPath/$child", File(targetDir, child))
        }
    }

    private fun copyAssetFile(assetPath: String, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent directory for ${targetFile.absolutePath}")
        }
        context.assets.open(assetPath).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun GeneratedAudio.toShortPcm(): ShortArray {
        return samples.map { sample ->
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            (clamped * 32767.0f).roundToInt().toShort()
        }.toShortArray()
    }
}
