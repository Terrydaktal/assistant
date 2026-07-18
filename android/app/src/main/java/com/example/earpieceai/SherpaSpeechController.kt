package com.example.earpieceai

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class SherpaSpeechController(private val engine: SherpaTtsEngine) {
    companion object {
        private const val TAG = "SherpaSpeechController"
        private const val FIRST_CHUNK_MAX_CHARS = 240
        private const val NEXT_CHUNK_MAX_CHARS = 420
    }

    interface Listener {
        fun onStart(totalDurationMs: Long)
        fun onProgress(positionMs: Long, totalDurationMs: Long, spokenCharIndex: Int)
        fun onComplete()
        fun onError(message: String, throwable: Throwable? = null)
    }

    private data class SpeechChunk(
        val text: String,
        val startCharIndex: Int
    )

    private data class SynthesizedChunk(
        val chunk: SpeechChunk,
        val pcm: ShortArray,
        val sampleRate: Int
    ) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else ((pcm.size * 1000L) / sampleRate).coerceAtLeast(0L)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player = PcmAudioPlayer()
    private val playbackSessionId = AtomicInteger(0)

    @Volatile
    private var currentTotalDurationMs: Long = 0L

    fun speak(text: String, voice: SherpaVoiceCatalog.VoiceModel, speed: Float, listener: Listener) {
        stop()
        val sessionId = playbackSessionId.incrementAndGet()
        scope.launch {
            try {
                val chunks = splitTextIntoChunks(text)
                if (chunks.isEmpty()) {
                    listener.onError("Speech text is empty")
                    return@launch
                }
                Log.i(TAG, "Speaking ${chunks.size} Sherpa chunk(s) for ${text.length} chars")
                val firstStartedAt = SystemClock.elapsedRealtime()
                val firstChunk = withContext(Dispatchers.Default) {
                    synthesizeChunk(chunks.first(), voice, speed)
                }
                Log.i(
                    TAG,
                    "First Sherpa chunk synthesized in ${SystemClock.elapsedRealtime() - firstStartedAt}ms chunkChars=${firstChunk.chunk.text.length}"
                )
                val nextDeferred = if (chunks.size > 1) {
                    scope.async(Dispatchers.Default) { synthesizeChunk(chunks[1], voice, speed) }
                } else {
                    null
                }
                playChunkSequence(
                    sessionId = sessionId,
                    allChunks = chunks,
                    totalChars = text.length,
                    voice = voice,
                    speed = speed,
                    listener = listener,
                    index = 0,
                    current = firstChunk,
                    nextDeferred = nextDeferred,
                    completedDurationMs = 0L,
                    synthesizedDurationMs = firstChunk.durationMs,
                    synthesizedChars = firstChunk.chunk.text.length
                )
            } catch (t: Throwable) {
                listener.onError("Sherpa Piper synthesis failed", t)
            }
        }
    }

    fun stop() {
        playbackSessionId.incrementAndGet()
        player.stop()
    }

    fun release() {
        player.release()
        engine.release()
        scope.cancel()
    }

    private fun playChunkSequence(
        sessionId: Int,
        allChunks: List<SpeechChunk>,
        totalChars: Int,
        voice: SherpaVoiceCatalog.VoiceModel,
        speed: Float,
        listener: Listener,
        index: Int,
        current: SynthesizedChunk,
        nextDeferred: kotlinx.coroutines.Deferred<SynthesizedChunk>?,
        completedDurationMs: Long,
        synthesizedDurationMs: Long,
        synthesizedChars: Int
    ) {
        if (sessionId != playbackSessionId.get()) {
            return
        }
        currentTotalDurationMs = estimateTotalDurationMs(totalChars, synthesizedDurationMs, synthesizedChars)
        player.play(current.sampleRate, current.pcm, 0, object : PcmAudioPlayer.Listener {
            override fun onStart() {
                if (index == 0) {
                    listener.onStart(currentTotalDurationMs)
                }
            }

            override fun onProgress(absoluteFrame: Int, positionMs: Long) {
                val absolutePositionMs = completedDurationMs + positionMs
                val currentChunkSpokenChars = if (current.durationMs <= 0L) {
                    current.chunk.text.length
                } else {
                    ((positionMs.toDouble() / current.durationMs.toDouble()) * current.chunk.text.length)
                        .toInt()
                        .coerceIn(0, current.chunk.text.length)
                }
                listener.onProgress(
                    absolutePositionMs,
                    currentTotalDurationMs,
                    (current.chunk.startCharIndex + currentChunkSpokenChars).coerceIn(0, totalChars)
                )
            }

            override fun onComplete() {
                if (sessionId != playbackSessionId.get()) {
                    return
                }
                val nextIndex = index + 1
                if (nextIndex >= allChunks.size) {
                    listener.onComplete()
                    return
                }
                scope.launch {
                    try {
                        val next = nextDeferred?.await() ?: withContext(Dispatchers.Default) {
                            synthesizeChunk(allChunks[nextIndex], voice, speed)
                        }
                        val futureDeferred = if (nextIndex + 1 < allChunks.size) {
                            scope.async(Dispatchers.Default) {
                                synthesizeChunk(allChunks[nextIndex + 1], voice, speed)
                            }
                        } else {
                            null
                        }
                        playChunkSequence(
                            sessionId = sessionId,
                            allChunks = allChunks,
                            totalChars = totalChars,
                            voice = voice,
                            speed = speed,
                            listener = listener,
                            index = nextIndex,
                            current = next,
                            nextDeferred = futureDeferred,
                            completedDurationMs = completedDurationMs + current.durationMs,
                            synthesizedDurationMs = synthesizedDurationMs + next.durationMs,
                            synthesizedChars = synthesizedChars + next.chunk.text.length
                        )
                    } catch (t: Throwable) {
                        listener.onError("Sherpa Piper synthesis failed", t)
                    }
                }
            }

            override fun onError(message: String, throwable: Throwable?) {
                listener.onError(message, throwable)
            }
        })
    }

    private fun synthesizeChunk(
        chunk: SpeechChunk,
        voice: SherpaVoiceCatalog.VoiceModel,
        speed: Float
    ): SynthesizedChunk {
        val synthesis = engine.synthesize(chunk.text, voice, speed)
        return SynthesizedChunk(
            chunk = chunk,
            pcm = synthesis.pcm,
            sampleRate = synthesis.sampleRate
        )
    }

    private fun estimateTotalDurationMs(
        totalChars: Int,
        synthesizedDurationMs: Long,
        synthesizedChars: Int
    ): Long {
        if (totalChars <= 0 || synthesizedChars <= 0) {
            return synthesizedDurationMs.coerceAtLeast(0L)
        }
        return ((synthesizedDurationMs.toDouble() / synthesizedChars.toDouble()) * totalChars.toDouble())
            .toLong()
            .coerceAtLeast(synthesizedDurationMs)
    }

    private fun splitTextIntoChunks(text: String): List<SpeechChunk> {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        val chunks = mutableListOf<SpeechChunk>()
        var cursor = 0
        while (cursor < normalized.length) {
            val maxChars = if (chunks.isEmpty()) FIRST_CHUNK_MAX_CHARS else NEXT_CHUNK_MAX_CHARS
            val remaining = normalized.length - cursor
            val desired = minOf(maxChars, remaining)
            var endExclusive = cursor + desired
            if (endExclusive < normalized.length) {
                val sentenceBreak = normalized.lastIndexOfAny(charArrayOf('.', '!', '?'), endExclusive - 1)
                val whitespaceBreak = normalized.lastIndexOf(' ', endExclusive - 1)
                endExclusive = when {
                    sentenceBreak >= cursor + (desired / 2) -> sentenceBreak + 1
                    whitespaceBreak > cursor -> whitespaceBreak
                    else -> endExclusive
                }
            }
            val chunkText = normalized.substring(cursor, endExclusive).trim()
            if (chunkText.isNotEmpty()) {
                val startIndex = normalized.indexOf(chunkText, cursor).coerceAtLeast(cursor)
                chunks.add(SpeechChunk(chunkText, startIndex))
            }
            cursor = endExclusive
            while (cursor < normalized.length && normalized[cursor].isWhitespace()) {
                cursor += 1
            }
        }
        return chunks
    }
}
