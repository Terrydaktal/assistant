package com.example.earpieceai

import android.content.Context
import android.database.Cursor
import android.media.AudioFormat
import android.os.Build
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object ImportedAudioTailSender {
    private const val TAG = "ImportedAudioTail"
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val TRANSCRIPTION_TIMEOUT_SECONDS = 1_800L

    data class Result(
        val transcription: String,
        val timingSummary: String
    )

    data class ProgressUpdate(
        val stage: String,
        val elapsedMs: Long,
        val stageMs: Long = -1,
        val detail: String = ""
    )

    private data class WhisperServerTimings(
        val uploadBodyReadMs: Long = -1,
        val serverTranscribeMs: Long = -1,
        val postprocessMs: Long = -1,
        val serverTotalMs: Long = -1
    )

    private data class WhisperResult(
        val transcription: String,
        val roundTripMs: Long,
        val timings: WhisperServerTimings
    )

    private class WhisperRequestException(
        message: String,
        val roundTripMs: Long,
        val timings: WhisperServerTimings,
        cause: Throwable? = null
    ) : IOException(message, cause)

    private class ProgressFileRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onUploadComplete: () -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.source().use { source -> sink.writeAll(source) }
            onUploadComplete()
        }
    }

    private data class DecodedAudio(
        val pcmFile: File,
        val sampleRate: Int,
        val channelCount: Int,
        val durationUs: Long,
        val sourceBytes: Long,
        val decodeMode: String,
        val openMs: Long,
        val decodeMs: Long
    )

    suspend fun sendTail(
        context: Context,
        uri: Uri,
        tailSeconds: Int,
        whisperBaseUrl: String,
        onProgress: (ProgressUpdate) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val requestStartedAt = System.currentTimeMillis()
        fun report(stage: String, stageMs: Long = -1, detail: String = "") {
            runCatching {
                onProgress(
                    ProgressUpdate(
                        stage = stage,
                        elapsedMs = System.currentTimeMillis() - requestStartedAt,
                        stageMs = stageMs,
                        detail = detail
                    )
                )
            }.onFailure { error ->
                Log.w(TAG, "Imported audio progress callback failed", error)
            }
        }
        var snapshotFile: File? = null
        var uploadFile: File? = null
        var compressedTail: CompressedAudioTailExtractor.Result? = null
        var decoded: DecodedAudio? = null
        var tailExtractMs = -1L
        var flacEncodeMs = -1L
        var audioFormat = "not_started"
        var clientUploadMs = -1L
        var whisperRoundTripMs = -1L
        var serverTimings = WhisperServerTimings()
        try {
            report("Request started", 0, "Preparing the last $tailSeconds seconds")
            val extractionStartedAt = System.currentTimeMillis()
            report("Extracting compressed recording tail")
            try {
                compressedTail = CompressedAudioTailExtractor.extract(context, uri, tailSeconds)
                uploadFile = compressedTail.file
                audioFormat = compressedTail.format
                tailExtractMs = System.currentTimeMillis() - extractionStartedAt
                report(
                    stage = "Compressed recording tail ready",
                    stageMs = tailExtractMs,
                    detail = "${formatSeconds(compressedTail.durationUs / 1_000_000.0)} audio, " +
                        "${formatBytes(compressedTail.file.length())} $audioFormat"
                )
            } catch (compressedCopyError: Exception) {
                Log.w(TAG, "Compressed tail copy unavailable; falling back to decode and FLAC", compressedCopyError)
                report(
                    "Compressed copy unavailable",
                    detail = "Falling back to PCM decode and FLAC"
                )
                decoded = try {
                    decodeTailFromUri(context, uri, tailSeconds)
                } catch (fastPathError: Exception) {
                    Log.w(TAG, "Fast URI tail decode failed; falling back to full snapshot", fastPathError)
                    report("Direct file access failed", detail = "Creating a readable snapshot")
                    snapshotFile = copyUriSnapshot(context, uri)
                    decodeTailFromFile(context.cacheDir, snapshotFile, tailSeconds, "full_snapshot")
                }
                tailExtractMs = System.currentTimeMillis() - extractionStartedAt
                val decodedAudio = decoded ?: throw IOException("Audio tail extraction returned no data")
                report(
                    stage = "Recording tail decoded",
                    stageMs = tailExtractMs,
                    detail = "${formatSeconds(decodedAudio.durationUs / 1_000_000.0)} audio, " +
                        "${formatBytes(decodedAudio.pcmFile.length())} temporary PCM"
                )
                val flacStartedAt = System.currentTimeMillis()
                report("Encoding fallback FLAC")
                uploadFile = AudioFlacEncoder.encodePcmFileToFlac(
                    cacheDir = context.cacheDir,
                    pcmFile = decodedAudio.pcmFile,
                    sampleRate = decodedAudio.sampleRate,
                    channels = decodedAudio.channelCount
                )
                flacEncodeMs = System.currentTimeMillis() - flacStartedAt
                audioFormat = "flac"
                val encodedFile = uploadFile ?: throw IOException("FLAC encoder unavailable")
                report(
                    stage = "Fallback FLAC encoding finished",
                    stageMs = flacEncodeMs,
                    detail = formatBytes(encodedFile.length())
                )
            }
            val preparedAudioFile = uploadFile ?: throw IOException("Audio tail preparation returned no file")
            val clipDurationUs = compressedTail?.durationUs ?: decoded?.durationUs ?: 0L

            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TRANSCRIPTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()

            val uploadStartedAt = System.currentTimeMillis()
            report("Uploading audio to computer")
            val requestBody = ProgressFileRequestBody(
                file = preparedAudioFile,
                mediaType = "application/octet-stream".toMediaType()
            ) {
                clientUploadMs = System.currentTimeMillis() - uploadStartedAt
                report(
                    stage = "Upload sent; waiting for Whisper result",
                    stageMs = clientUploadMs,
                    detail = "${formatBytes(preparedAudioFile.length())} $audioFormat"
                )
            }
            val request = Request.Builder()
                .url("$whisperBaseUrl/transcribe_raw")
                .post(requestBody)
                .build()

            val whisperResult = transcribe(client, request)
            whisperRoundTripMs = whisperResult.roundTripMs
            serverTimings = whisperResult.timings
            if (serverTimings.uploadBodyReadMs >= 0) {
                report(
                    stage = "Computer received audio",
                    stageMs = serverTimings.uploadBodyReadMs,
                    detail = "Server upload read"
                )
            }
            if (serverTimings.serverTranscribeMs >= 0) {
                val speed = if (serverTimings.serverTranscribeMs > 0) {
                    clipDurationUs / 1000.0 / serverTimings.serverTranscribeMs
                } else {
                    0.0
                }
                report(
                    stage = "Whisper transcription finished",
                    stageMs = serverTimings.serverTranscribeMs,
                    detail = if (speed > 0) "${String.format(java.util.Locale.US, "%.1fx", speed)} realtime" else ""
                )
            }
            if (serverTimings.postprocessMs >= 0) {
                report(
                    stage = "Text post-processing finished",
                    stageMs = serverTimings.postprocessMs
                )
            }
            report(
                stage = "Computer returned transcription",
                stageMs = whisperRoundTripMs,
                detail = "Complete HTTP round trip"
            )
            val timing = buildTimingSummary(
                outcome = "success",
                tailSeconds = tailSeconds,
                decoded = decoded,
                compressedTail = compressedTail,
                tailExtractMs = tailExtractMs,
                flacEncodeMs = flacEncodeMs,
                clientUploadMs = clientUploadMs,
                audioFormat = audioFormat,
                audioBytes = preparedAudioFile.length(),
                snapshotBytes = snapshotFile?.length() ?: 0,
                whisperRoundTripMs = whisperRoundTripMs,
                serverTimings = serverTimings,
                totalMs = System.currentTimeMillis() - requestStartedAt
            )
            DebugTimingStore.saveLastTiming(context, timing)
            Log.d(TAG, timing)
            report("Transcription ready", detail = "Copying to the phone clipboard")
            Result(
                transcription = whisperResult.transcription,
                timingSummary = timing
            )
        } catch (error: Exception) {
            if (error is WhisperRequestException) {
                whisperRoundTripMs = error.roundTripMs
                serverTimings = error.timings
            }
            val timing = buildTimingSummary(
                outcome = "failed:${error.javaClass.simpleName}",
                tailSeconds = tailSeconds,
                decoded = decoded,
                compressedTail = compressedTail,
                tailExtractMs = tailExtractMs,
                flacEncodeMs = flacEncodeMs,
                clientUploadMs = clientUploadMs,
                audioFormat = audioFormat,
                audioBytes = uploadFile?.length() ?: 0,
                snapshotBytes = snapshotFile?.length() ?: 0,
                whisperRoundTripMs = whisperRoundTripMs,
                serverTimings = serverTimings,
                totalMs = System.currentTimeMillis() - requestStartedAt
            )
            DebugTimingStore.saveLastTiming(context, timing)
            Log.e(TAG, timing, error)
            report("Request failed", detail = error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            decoded?.pcmFile?.delete()
            snapshotFile?.delete()
            uploadFile?.delete()
        }
    }

    private fun decodeTailFromUri(context: Context, uri: Uri, tailSeconds: Int): DecodedAudio {
        val openStartedAt = System.currentTimeMillis()
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val statSize = descriptor.parcelFileDescriptor.statSize
            val length = when {
                descriptor.length > 0L -> descriptor.length
                statSize > 0L -> statSize
                else -> -1L
            }
            if (length <= 0L) {
                throw IOException("Selected audio file length is unavailable")
            }
            val openMs = System.currentTimeMillis() - openStartedAt
            val decodeStartedAt = System.currentTimeMillis()
            val extractor = MediaExtractor()
            var extractorTransferred = false
            try {
                extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, length)
                extractorTransferred = true
                return decodeTailWithExtractor(
                    extractor = extractor,
                    tailSeconds = tailSeconds,
                    sourceBytes = length,
                    decodeMode = "uri_seek",
                    openMs = openMs,
                    decodeStartedAt = decodeStartedAt,
                    cacheDir = context.cacheDir
                ).also {
                    Log.d(TAG, "Fast URI tail decode completed sourceBytes=$length")
                }
            } finally {
                if (!extractorTransferred) {
                    extractor.release()
                }
            }
        } ?: throw IOException("Unable to open audio asset descriptor")
    }

    private fun copyUriSnapshot(context: Context, uri: Uri): File {
        val extension = guessFileExtension(context, uri)
        val snapshot = File.createTempFile("import_snapshot_", extension, context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            snapshot.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Unable to open audio file")
        Log.d(TAG, "Created audio snapshot ${snapshot.absolutePath} (${snapshot.length()} bytes)")
        return snapshot
    }

    private fun guessFileExtension(context: Context, uri: Uri): String {
        val displayName = queryDisplayName(context, uri)?.lowercase().orEmpty()
        return when {
            displayName.endsWith(".m4a") -> ".m4a"
            displayName.endsWith(".mp3") -> ".mp3"
            context.contentResolver.getType(uri)?.contains("mp4", ignoreCase = true) == true -> ".m4a"
            else -> ".mp3"
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val cursor: Cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) return null
            return it.getString(index)
        }
    }

    private fun decodeTailFromFile(
        cacheDir: File,
        snapshotFile: File?,
        tailSeconds: Int,
        decodeMode: String
    ): DecodedAudio {
        val file = snapshotFile ?: throw IOException("Snapshot file missing")
        val openStartedAt = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var extractorTransferred = false
        FileInputStream(file).use { input ->
            val openMs = System.currentTimeMillis() - openStartedAt
            val decodeStartedAt = System.currentTimeMillis()
            try {
                extractor.setDataSource(input.fd)
                extractorTransferred = true
                return decodeTailWithExtractor(
                    extractor = extractor,
                    tailSeconds = tailSeconds,
                    sourceBytes = file.length(),
                    decodeMode = decodeMode,
                    openMs = openMs,
                    decodeStartedAt = decodeStartedAt,
                    cacheDir = cacheDir
                )
            } finally {
                if (!extractorTransferred) {
                    extractor.release()
                }
            }
        }
    }

    private fun decodeTailWithExtractor(
        extractor: MediaExtractor,
        tailSeconds: Int,
        sourceBytes: Long,
        decodeMode: String,
        openMs: Long,
        decodeStartedAt: Long,
        cacheDir: File
    ): DecodedAudio {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track found in selected file")

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IOException("Audio track MIME type missing")
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        val targetStartUs = if (durationUs > 0L) {
            (durationUs - tailSeconds * 1_000_000L).coerceAtLeast(0L)
        } else {
            0L
        }

        extractor.selectTrack(trackIndex)
        if (targetStartUs > 0L) {
            extractor.seekTo(targetStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        var outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcmFile = File.createTempFile("imported_tail_", ".pcm", cacheDir)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            FileOutputStream(pcmFile).use { output ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: throw IOException("Decoder input buffer unavailable")
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            val pcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                                decoder.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ) {
                                decoder.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                                throw IOException("Only PCM 16-bit decoder output is supported")
                            }
                            if (bufferInfo.presentationTimeUs + 1 < targetStartUs) {
                                // Skip buffers still fully before the requested tail window.
                            } else if (bufferInfo.presentationTimeUs < targetStartUs) {
                                val frameSize = outputChannels * 2
                                val bytesPerUs = (outputSampleRate * frameSize).toDouble() / 1_000_000.0
                                val skipBytes = ((targetStartUs - bufferInfo.presentationTimeUs) * bytesPerUs)
                                    .toInt()
                                    .coerceAtMost(bytes.size)
                                val alignedSkip = skipBytes - (skipBytes % frameSize)
                                output.write(bytes, alignedSkip, bytes.size - alignedSkip)
                            } else {
                                output.write(bytes)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    }
                }
            }
        } catch (error: Throwable) {
            pcmFile.delete()
            throw error
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            decoder.release()
            extractor.release()
        }

        if (pcmFile.length() == 0L) {
            pcmFile.delete()
            throw IOException("No decodable audio found in the selected tail segment")
        }
        val sampleCount = pcmFile.length() / 2L
        val actualDurationUs = sampleCount * 1_000_000L / (outputSampleRate.toLong() * outputChannels.toLong())
        val decodeMs = System.currentTimeMillis() - decodeStartedAt
        Log.d(
            TAG,
            "Decoded tail mode=$decodeMode sourceBytes=$sourceBytes samples=$sampleCount " +
                "sampleRate=$outputSampleRate channels=$outputChannels durationUs=$actualDurationUs " +
                "openMs=$openMs decodeMs=$decodeMs"
        )
        return DecodedAudio(
            pcmFile = pcmFile,
            sampleRate = outputSampleRate,
            channelCount = outputChannels,
            durationUs = actualDurationUs,
            sourceBytes = sourceBytes,
            decodeMode = decodeMode,
            openMs = openMs,
            decodeMs = decodeMs
        )
    }

    private fun transcribe(client: OkHttpClient, request: Request): WhisperResult {
        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                val roundTripMs = System.currentTimeMillis() - startedAt
                val bodyText = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(bodyText) }.getOrElse { JSONObject() }
                val timings = parseWhisperTimings(json.optJSONObject("timings"))
                if (!response.isSuccessful) {
                    throw WhisperRequestException(
                        message = "Whisper service failed: ${response.code} ${response.message} " +
                            json.optString("error", bodyText),
                        roundTripMs = roundTripMs,
                        timings = timings
                    )
                }
                val transcription = json.optString("text", "").trim()
                if (transcription.isBlank()) {
                    throw WhisperRequestException(
                        "Whisper service returned an empty transcription",
                        roundTripMs,
                        timings
                    )
                }
                return WhisperResult(transcription, roundTripMs, timings)
            }
        } catch (error: WhisperRequestException) {
            throw error
        } catch (error: Exception) {
            throw WhisperRequestException(
                message = error.message ?: "Whisper request failed",
                roundTripMs = System.currentTimeMillis() - startedAt,
                timings = WhisperServerTimings(),
                cause = error
            )
        }
    }

    private fun parseWhisperTimings(json: JSONObject?): WhisperServerTimings {
        if (json == null) return WhisperServerTimings()
        return WhisperServerTimings(
            uploadBodyReadMs = json.optLong("upload_body_read_ms", -1),
            serverTranscribeMs = json.optLong("server_transcribe_ms", -1),
            postprocessMs = json.optLong("postprocess_ms", -1),
            serverTotalMs = json.optLong("server_total_ms", -1)
        )
    }

    private fun buildTimingSummary(
        outcome: String,
        tailSeconds: Int,
        decoded: DecodedAudio?,
        compressedTail: CompressedAudioTailExtractor.Result?,
        tailExtractMs: Long,
        flacEncodeMs: Long,
        clientUploadMs: Long,
        audioFormat: String,
        audioBytes: Long,
        snapshotBytes: Long,
        whisperRoundTripMs: Long,
        serverTimings: WhisperServerTimings,
        totalMs: Long
    ): String {
        val clipDurationUs = compressedTail?.durationUs ?: decoded?.durationUs ?: 0L
        val clipSeconds = clipDurationUs / 1_000_000.0
        val preparationMode = compressedTail?.let { "compressed_${it.format}_copy" }
            ?: decoded?.decodeMode
            ?: "not_started"
        val sourceBytes = compressedTail?.sourceBytes ?: decoded?.sourceBytes ?: 0L
        val openMs = compressedTail?.openMs ?: decoded?.openMs ?: -1L
        val networkOverheadMs = if (whisperRoundTripMs >= 0 && serverTimings.serverTotalMs >= 0) {
            (whisperRoundTripMs - serverTimings.serverTotalMs).coerceAtLeast(0)
        } else {
            -1
        }
        return buildString {
            append("Imported audio timing outcome=$outcome ")
            append("tail_request_s=$tailSeconds ")
            append("decoded_clip_s=${"%.2f".format(java.util.Locale.US, clipSeconds)} ")
            append("prepare_mode=$preparationMode ")
            append("source_bytes=$sourceBytes ")
            append("open_ms=$openMs ")
            append("decode_ms=${decoded?.decodeMs ?: -1} ")
            append("tail_extract_ms=$tailExtractMs ")
            append("snapshot_bytes=$snapshotBytes ")
            append("audio_format=$audioFormat ")
            append("audio_bytes=$audioBytes ")
            append("flac_encode_ms=$flacEncodeMs ")
            append("client_upload_ms=$clientUploadMs ")
            append("upload_body_read_ms=${serverTimings.uploadBodyReadMs} ")
            append("server_transcribe_ms=${serverTimings.serverTranscribeMs} ")
            append("server_postprocess_ms=${serverTimings.postprocessMs} ")
            append("server_total_ms=${serverTimings.serverTotalMs} ")
            append("whisper_round_trip_ms=$whisperRoundTripMs ")
            append("network_response_overhead_ms=$networkOverheadMs ")
            append("total_ms=$totalMs")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val mib = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MiB", mib)
    }

    private fun formatSeconds(seconds: Double): String {
        return if (seconds >= 60.0) {
            String.format(java.util.Locale.US, "%.1f min", seconds / 60.0)
        } else {
            String.format(java.util.Locale.US, "%.1f sec", seconds)
        }
    }
}
