package com.example.earpieceai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioRecorder {
    private val TAG = "AudioRecorder"

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    private val audioBuffer = mutableListOf<Float>()
    private val bufferLock = Any()
    private var amplitudeListener: ((Float) -> Unit)? = null
    private var lastAmplitudeDispatchMs = 0L

    fun setAmplitudeListener(listener: ((Float) -> Unit)?) {
        amplitudeListener = listener
    }

    fun startRecording(scope: CoroutineScope) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            audioRecord?.release()
            audioRecord = null
            throw IllegalStateException("Failed to initialize audio recorder")
        }

        synchronized(bufferLock) {
            audioBuffer.clear()
        }
        lastAmplitudeDispatchMs = 0L
        isRecording.set(true)

        audioRecord?.startRecording()
        Log.d(TAG, "Audio recording started")

        recordingJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val shortBuffer = ShortArray(minBufferSize)

            try {
                while (isRecording.get() && isActive) {
                    val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0

                    if (readResult > 0) {
                        val listener = amplitudeListener
                        if (listener != null) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastAmplitudeDispatchMs >= 40) {
                                listener.invoke(calculateRms(shortBuffer, readResult))
                                lastAmplitudeDispatchMs = now
                            }
                        }
                        synchronized(bufferLock) {
                            for (i in 0 until readResult) {
                                audioBuffer.add(shortBuffer[i] / 32768.0f)
                            }
                        }
                    } else if (readResult < 0) {
                        Log.e(TAG, "AudioRecord.read() failed with error: $readResult")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording loop", e)
            } finally {
                stopInternal()
            }
        }
    }

    suspend fun stopRecordingAndGetData(): FloatArray? {
        if (!isRecording.get()) {
            Log.w(TAG, "Not recording")
            return null
        }

        Log.d(TAG, "Stopping recording...")
        isRecording.set(false)

        // Wait for recording job to finish
        recordingJob?.join()
        recordingJob = null

        val result = synchronized(bufferLock) {
            if (audioBuffer.isNotEmpty()) {
                audioBuffer.toFloatArray().also {
                    Log.d(TAG, "Recording stopped, captured ${it.size} samples (${it.size / 16000.0f}s)")
                }
            } else {
                Log.w(TAG, "Recording stopped, but audio buffer is empty")
                null
            }
        }

        return result
    }

    private fun stopInternal() {
        try {
            audioRecord?.stop()
            Log.d(TAG, "AudioRecord stopped")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        try {
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "AudioRecord released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
    }

    private fun calculateRms(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val mean = if (count > 0) sum / count else 0.0
        return (sqrt(mean) / 32768.0).toFloat()
    }

    fun isRecording(): Boolean {
        return isRecording.get()
    }

    fun getRecordingDuration(): Float {
        synchronized(bufferLock) {
            return audioBuffer.size / SAMPLE_RATE.toFloat()
        }
    }

    fun cleanup() {
        if (isRecording.get()) {
            Log.d(TAG, "Force cleanup while recording")
            isRecording.set(false)

            try {
                recordingJob?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling recording job", e)
            }

            stopInternal()
        }

        synchronized(bufferLock) {
            audioBuffer.clear()
        }

        recordingJob = null
        Log.d(TAG, "AudioRecorder cleaned up")
    }
}
