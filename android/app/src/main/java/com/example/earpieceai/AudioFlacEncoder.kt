package com.example.earpieceai

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object AudioFlacEncoder {
    private const val TAG = "AudioFlacEncoder"

    fun encodePcmFileToFlac(cacheDir: File, pcmFile: File, sampleRate: Int, channels: Int): File? {
        val format = MediaFormat.createAudioFormat("audio/flac", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 0)
        }
        val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
        if (codecName.isNullOrBlank()) {
            Log.e(TAG, "No FLAC encoder available on this device")
            return null
        }

        val outputFile = File.createTempFile("audio_", ".flac", cacheDir)
        outputFile.deleteOnExit()

        var codec: MediaCodec? = null
        try {
            Log.d(TAG, "Using FLAC encoder '$codecName' at compression level 0")
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            FileInputStream(pcmFile).use { input ->
                FileOutputStream(outputFile).use { output ->
                    val bufferInfo = MediaCodec.BufferInfo()
                    val frameSize = channels * 2
                    var inputBytesQueued = 0L
                    var inputDone = false
                    var outputDone = false

                    while (!outputDone) {
                        if (!inputDone) {
                            val inputIndex = codec.dequeueInputBuffer(10_000)
                            if (inputIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputIndex)
                                inputBuffer?.clear()
                                if (inputBuffer != null) {
                                val alignedLimit = inputBuffer.remaining() - (inputBuffer.remaining() % frameSize)
                                inputBuffer.limit(inputBuffer.position() + alignedLimit)
                                val size = input.channel.read(inputBuffer)
                                if (size > 0) {
                                    val presentationTimeUs = inputBytesQueued * 1_000_000L /
                                        (sampleRate.toLong() * frameSize.toLong())
                                    inputBytesQueued += size
                                    codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                                } else {
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        inputBytesQueued * 1_000_000L /
                                            (sampleRate.toLong() * frameSize.toLong()),
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                }
                                } else {
                                    throw IllegalStateException("FLAC encoder input buffer unavailable")
                                }
                            }
                        }

                        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                        when {
                        outputIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                while (outputBuffer.hasRemaining()) output.channel.write(outputBuffer)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "FLAC encoder output format changed: ${codec.outputFormat}")
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // keep looping
                        }
                        }
                    }
                }
            }

            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "FLAC encoding failed", e)
            outputFile.delete()
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
        }
    }
}
