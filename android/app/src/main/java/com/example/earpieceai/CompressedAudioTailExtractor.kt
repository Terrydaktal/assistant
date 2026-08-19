package com.example.earpieceai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

internal enum class CompressedTailTransport(
    val extension: String,
    val displayName: String
) {
    M4A("m4a", "m4a"),
    MP3("mp3", "mp3"),
    OGG_OPUS("ogg", "ogg-opus")
}

internal fun compressedTailTransportForMime(
    mime: String,
    supportsOggMuxer: Boolean
): CompressedTailTransport? {
    return when (mime.lowercase()) {
        "audio/mp4a-latm", "audio/aac" -> CompressedTailTransport.M4A
        "audio/mpeg" -> CompressedTailTransport.MP3
        "audio/opus" -> CompressedTailTransport.OGG_OPUS.takeIf { supportsOggMuxer }
        else -> null
    }
}

object CompressedAudioTailExtractor {
    data class Result(
        val file: File,
        val format: String,
        val durationUs: Long,
        val sourceBytes: Long,
        val openMs: Long,
        val extractMs: Long
    )

    fun extract(context: Context, uri: Uri, tailSeconds: Int): Result {
        val openStartedAt = System.currentTimeMillis()
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val statSize = descriptor.parcelFileDescriptor.statSize
            val length = when {
                descriptor.length > 0L -> descriptor.length
                statSize > 0L -> statSize
                else -> throw IOException("Selected audio file length is unavailable")
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, length)
                val openMs = System.currentTimeMillis() - openStartedAt
                val extractStartedAt = System.currentTimeMillis()
                val result = extractWithExtractor(context.cacheDir, extractor, tailSeconds, length, openMs)
                return result.copy(extractMs = System.currentTimeMillis() - extractStartedAt)
            } finally {
                extractor.release()
            }
        }
        throw IOException("Unable to open audio asset descriptor")
    }

    private fun extractWithExtractor(
        cacheDir: File,
        extractor: MediaExtractor,
        tailSeconds: Int,
        sourceBytes: Long,
        openMs: Long
    ): Result {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track found in selected file")
        val trackFormat = extractor.getTrackFormat(trackIndex)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IOException("Audio track MIME type missing")
        val transport = compressedTailTransportForMime(
            mime = mime,
            supportsOggMuxer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) ?: throw IOException("Compressed tail copy is unsupported for $mime")
        val durationUs = trackFormat.longOrZero(MediaFormat.KEY_DURATION)
        if (durationUs <= 0L) {
            throw IOException("Audio duration is unavailable for compressed tail extraction")
        }
        val targetStartUs = (durationUs - tailSeconds * 1_000_000L).coerceAtLeast(0L)
        extractor.selectTrack(trackIndex)
        extractor.seekTo(targetStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        skipSamplesBefore(extractor, targetStartUs)

        val capacity = trackFormat.intOrZero(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
        val buffer = ByteBuffer.allocateDirect(capacity)
        val output = File.createTempFile("imported_tail_", ".${transport.extension}", cacheDir)
        return try {
            val firstSampleUs = extractor.sampleTime.coerceAtLeast(targetStartUs)
            when (transport) {
                CompressedTailTransport.M4A -> writeMuxedAudio(
                    output,
                    extractor,
                    trackFormat,
                    buffer,
                    firstSampleUs,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                CompressedTailTransport.MP3 -> writeMp3(output, extractor, buffer)
                CompressedTailTransport.OGG_OPUS -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        throw IOException("Ogg Opus muxing requires Android 10 or newer")
                    }
                    writeOggOpus(output, extractor, trackFormat, buffer, firstSampleUs)
                }
            }
            if (output.length() <= 0L) throw IOException("No compressed audio found in requested tail")
            Result(
                file = output,
                format = transport.displayName,
                durationUs = (durationUs - firstSampleUs).coerceAtLeast(0L),
                sourceBytes = sourceBytes,
                openMs = openMs,
                extractMs = 0L
            )
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun skipSamplesBefore(extractor: MediaExtractor, targetStartUs: Long) {
        while (extractor.sampleTime in 0 until targetStartUs) {
            if (!extractor.advance()) break
        }
    }

    private fun writeMuxedAudio(
        output: File,
        extractor: MediaExtractor,
        trackFormat: MediaFormat,
        buffer: ByteBuffer,
        firstSampleUs: Long,
        outputFormat: Int
    ) {
        val muxer = MediaMuxer(output.absolutePath, outputFormat)
        var started = false
        try {
            val outputTrack = muxer.addTrack(trackFormat)
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            while (extractor.sampleTime >= 0L) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.position(0)
                buffer.limit(size)
                info.set(
                    0,
                    size,
                    (extractor.sampleTime - firstSampleUs).coerceAtLeast(0L),
                    if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                )
                muxer.writeSampleData(outputTrack, buffer, info)
                if (!extractor.advance()) break
            }
        } finally {
            if (started) muxer.stop()
            muxer.release()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeOggOpus(
        output: File,
        extractor: MediaExtractor,
        trackFormat: MediaFormat,
        buffer: ByteBuffer,
        firstSampleUs: Long
    ) {
        writeMuxedAudio(
            output,
            extractor,
            trackFormat,
            buffer,
            firstSampleUs,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        )
    }

    private fun writeMp3(output: File, extractor: MediaExtractor, buffer: ByteBuffer) {
        FileOutputStream(output).use { stream ->
            while (extractor.sampleTime >= 0L) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.position(0)
                buffer.limit(size)
                while (buffer.hasRemaining()) stream.channel.write(buffer)
                if (!extractor.advance()) break
            }
        }
    }

    private fun MediaFormat.longOrZero(key: String): Long {
        return if (containsKey(key)) getLong(key) else 0L
    }

    private fun MediaFormat.intOrZero(key: String): Int {
        return if (containsKey(key)) getInteger(key) else 0
    }
}
