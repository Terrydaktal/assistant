package com.example.earpieceai

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

class GoogleTtsController(context: Context) {
    companion object {
        private const val TAG = "GoogleTtsController"
        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        private const val MAX_CHUNK_CHARS = 850
    }

    interface Listener {
        fun onStart()
        fun onProgress(spokenCharIndex: Int)
        fun onComplete()
        fun onError(message: String, throwable: Throwable? = null)
    }

    private data class Chunk(
        val utteranceId: String,
        val startOffset: Int,
        val text: String
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitializing = false
    @Volatile
    private var isReady = false
    private var lastInitError: String? = null
    private val pendingActions = mutableListOf<(Result<TextToSpeech>) -> Unit>()
    private var currentListener: Listener? = null
    private var currentGeneration = 0
    private var currentUtterances = emptyList<Chunk>()
    private val chunkByUtteranceId = mutableMapOf<String, Chunk>()

    fun warmUp(onComplete: ((Result<Unit>) -> Unit)? = null) {
        ensureReady { result ->
            if (result.isSuccess) {
                onComplete?.invoke(Result.success(Unit))
            } else {
                onComplete?.invoke(Result.failure(result.exceptionOrNull() ?: IllegalStateException("Google TTS unavailable")))
            }
        }
    }

    fun getVoiceOptions(callback: (Result<List<TtsVoicePreferences.VoiceOption>>) -> Unit) {
        ensureReady { result ->
            result.onSuccess { engine ->
                callback(Result.success(TtsVoicePreferences.buildVoiceOptions(engine)))
            }.onFailure { error ->
                callback(Result.failure(error))
            }
        }
    }

    fun applySavedVoice(callback: (Result<String>) -> Unit) {
        ensureReady { result ->
            result.onSuccess { engine ->
                try {
                    callback(Result.success(TtsVoicePreferences.applySavedVoice(appContext, engine)))
                } catch (error: Exception) {
                    callback(Result.failure(error))
                }
            }.onFailure { error ->
                callback(Result.failure(error))
            }
        }
    }

    fun speak(text: String, speechRate: Float, listener: Listener) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            listener.onError("Google TTS text is empty")
            return
        }
        ensureReady { result ->
            result.onSuccess { engine ->
                try {
                    currentGeneration += 1
                    currentListener = listener
                    engine.stop()
                    TtsVoicePreferences.applySavedVoice(appContext, engine)
                    engine.setSpeechRate(speechRate.coerceIn(0.6f, 1.8f))
                    val generation = currentGeneration
                    val chunks = splitIntoChunks(trimmed)
                    currentUtterances = chunks
                    chunkByUtteranceId.clear()
                    chunks.forEachIndexed { index, chunk ->
                        chunkByUtteranceId[chunk.utteranceId] = chunk
                        val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        val resultCode = engine.speak(chunk.text, queueMode, Bundle(), chunk.utteranceId)
                        if (resultCode != TextToSpeech.SUCCESS) {
                            throw IllegalStateException("Google TTS speak() returned $resultCode")
                        }
                    }
                    Log.d(TAG, "Queued ${chunks.size} Google TTS chunks for generation=$generation")
                } catch (error: Exception) {
                    currentListener = null
                    listener.onError("Google TTS playback failed", error)
                }
            }.onFailure { error ->
                listener.onError("Google TTS initialisation failed", error)
            }
        }
    }

    fun stop() {
        currentGeneration += 1
        currentListener = null
        chunkByUtteranceId.clear()
        currentUtterances = emptyList()
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
        isInitializing = false
    }

    private fun ensureReady(callback: (Result<TextToSpeech>) -> Unit) {
        val readyTts = tts
        if (isReady && readyTts != null) {
            callback(Result.success(readyTts))
            return
        }
        synchronized(pendingActions) {
            pendingActions.add(callback)
            if (isInitializing) {
                return
            }
            isInitializing = true
        }
        mainHandler.post {
            try {
                val engine = TextToSpeech(appContext, { status ->
                    val result = if (status == TextToSpeech.SUCCESS) {
                        val initializedTts = tts
                        if (initializedTts == null) {
                            Result.failure<TextToSpeech>(IllegalStateException("Google TTS engine was not created"))
                        } else {
                            initializedTts.language = Locale.UK
                            initializedTts.setOnUtteranceProgressListener(createProgressListener())
                            isReady = true
                            lastInitError = null
                            Result.success(initializedTts)
                        }
                    } else {
                        isReady = false
                        lastInitError = "Google TTS init status=$status"
                        Result.failure<TextToSpeech>(IllegalStateException(lastInitError))
                    }
                    finishInitialisation(result)
                }, GOOGLE_TTS_ENGINE)
                tts = engine
            } catch (error: Exception) {
                isReady = false
                lastInitError = error.message ?: "Google TTS creation failed"
                finishInitialisation(Result.failure(error))
            }
        }
    }

    private fun finishInitialisation(result: Result<TextToSpeech>) {
        isInitializing = false
        val callbacks = synchronized(pendingActions) {
            pendingActions.toList().also { pendingActions.clear() }
        }
        callbacks.forEach { it(result) }
    }

    private fun createProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val listener = currentListener ?: return
                if (!chunkByUtteranceId.containsKey(utteranceId)) return
                val firstUtteranceId = currentUtterances.firstOrNull()?.utteranceId ?: return
                if (utteranceId == firstUtteranceId) {
                    listener.onStart()
                }
            }

            override fun onDone(utteranceId: String) {
                val listener = currentListener ?: return
                if (!chunkByUtteranceId.containsKey(utteranceId)) return
                val lastUtteranceId = currentUtterances.lastOrNull()?.utteranceId ?: return
                if (utteranceId == lastUtteranceId) {
                    currentListener = null
                    chunkByUtteranceId.clear()
                    currentUtterances = emptyList()
                    mainHandler.post { listener.onComplete() }
                }
            }

            override fun onError(utteranceId: String) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                val listener = currentListener ?: return
                if (!chunkByUtteranceId.containsKey(utteranceId)) return
                currentListener = null
                chunkByUtteranceId.clear()
                currentUtterances = emptyList()
                mainHandler.post {
                    listener.onError("Google TTS playback failed", IllegalStateException("errorCode=$errorCode utteranceId=$utteranceId"))
                }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val listener = currentListener ?: return
                val chunk = chunkByUtteranceId[utteranceId] ?: return
                val absoluteIndex = (chunk.startOffset + start).coerceAtLeast(0)
                listener.onProgress(absoluteIndex)
            }
        }
    }

    private fun splitIntoChunks(text: String): List<Chunk> {
        if (text.length <= MAX_CHUNK_CHARS) {
            return listOf(Chunk(UUID.randomUUID().toString(), 0, text))
        }
        val chunks = mutableListOf<Chunk>()
        var cursor = 0
        while (cursor < text.length) {
            val maxEnd = (cursor + MAX_CHUNK_CHARS).coerceAtMost(text.length)
            var end = text.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ','), startIndex = maxEnd - 1)
            if (end < cursor + (MAX_CHUNK_CHARS / 2)) {
                end = text.lastIndexOf(' ', maxEnd - 1)
            }
            if (end < cursor) {
                end = maxEnd
            } else if (end < text.length) {
                end += 1
            }
            val chunkText = text.substring(cursor, end).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(Chunk(UUID.randomUUID().toString(), cursor, chunkText))
            }
            cursor = end
            while (cursor < text.length && text[cursor].isWhitespace()) {
                cursor += 1
            }
        }
        return if (chunks.isEmpty()) {
            listOf(Chunk(UUID.randomUUID().toString(), 0, text))
        } else {
            chunks
        }
    }
}
