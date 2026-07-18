package com.example.earpieceai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class PcmAudioPlayer {
    interface Listener {
        fun onStart()
        fun onProgress(absoluteFrame: Int, positionMs: Long)
        fun onComplete()
        fun onError(message: String, throwable: Throwable? = null)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: kotlinx.coroutines.Job? = null
    private var audioTrack: AudioTrack? = null
    private var playStartFrame: Int = 0
    private var sampleRate: Int = 0
    private var totalFramesToPlay: Int = 0

    @Volatile
    private var suppressCompletionCallback = false

    fun play(sampleRate: Int, pcm: ShortArray, startFrame: Int = 0, listener: Listener) {
        stop()

        if (pcm.isEmpty()) {
            listener.onError("PCM buffer is empty")
            return
        }

        try {
            val safeStartFrame = startFrame.coerceIn(0, pcm.size - 1)
            val framesToPlay = max(1, pcm.size - safeStartFrame)
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(framesToPlay * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(minBuffer)
                .build()

            val written = track.write(pcm, safeStartFrame, framesToPlay, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                track.release()
                listener.onError("AudioTrack.write() failed: $written")
                return
            }

            this.audioTrack = track
            this.playStartFrame = safeStartFrame
            this.sampleRate = sampleRate
            this.totalFramesToPlay = written
            this.suppressCompletionCallback = false

            track.play()
            listener.onStart()

            progressJob = scope.launch {
                try {
                    while (isActive) {
                        val activeTrack = audioTrack ?: break
                        val head = activeTrack.playbackHeadPosition.coerceAtMost(totalFramesToPlay)
                        val absoluteFrame = playStartFrame + head
                        val positionMs = ((absoluteFrame * 1000L) / sampleRate).coerceAtLeast(0L)
                        listener.onProgress(absoluteFrame, positionMs)
                        if (head >= totalFramesToPlay) {
                            break
                        }
                        delay(50)
                    }
                    val finalFrame = playStartFrame + totalFramesToPlay
                    val finalMs = ((finalFrame * 1000L) / sampleRate).coerceAtLeast(0L)
                    listener.onProgress(finalFrame, finalMs)
                    cleanupTrack()
                    if (!suppressCompletionCallback) {
                        listener.onComplete()
                    }
                } catch (t: Throwable) {
                    cleanupTrack()
                    if (t !is CancellationException && !suppressCompletionCallback) {
                        listener.onError("PCM playback failed", t)
                    }
                }
            }
        } catch (t: Throwable) {
            cleanupTrack()
            listener.onError("Failed to start PCM playback", t)
        }
    }

    fun stop() {
        suppressCompletionCallback = true
        progressJob?.cancel()
        progressJob = null
        cleanupTrack()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun cleanupTrack() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
            } catch (_: Exception) {
            }
            try {
                track.flush()
            } catch (_: Exception) {
            }
            track.release()
        }
    }
}
