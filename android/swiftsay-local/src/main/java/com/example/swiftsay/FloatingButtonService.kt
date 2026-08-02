package com.example.swiftsay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.res.ColorStateList
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.TimeUnit
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.FileInputStream

class FloatingButtonService : Service() {

    companion object {
        private const val TAG = "FloatingButtonService"
        private const val DEBUG_SAVE_WAV = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var recordButton: ImageButton
    private lateinit var waveformView: VoiceWaveformView
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioRecorder = AudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isRecording = false
    private var lastRecordingDurationSeconds = 0.0
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor())
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startTime = System.currentTimeMillis()

            Log.d(TAG, "Sending request to: ${request.url}")

            return try {
                val response = chain.proceed(request)
                val endTime = System.currentTimeMillis()

                Log.d(TAG, "Response from ${request.url} - Status: ${response.code}, Time: ${endTime - startTime}ms")

                response
            } catch (e: Exception) {
                val endTime = System.currentTimeMillis()
                Log.e(TAG, "Request to ${request.url} failed after ${endTime - startTime}ms: ${e.message}")
                throw e
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted")
            showToast("Overlay permission required", Toast.LENGTH_LONG)
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            stopSelf()
            return
        }

        startForeground(NotificationHelper.createNotificationId(), NotificationHelper.createForegroundNotification(this))

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.layout_floating_button, null)
        recordButton = floatingView.findViewById(R.id.record_button)
        waveformView = floatingView.findViewById(R.id.voice_waveform)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        try {
            windowManager.addView(floatingView, params)
            Log.d(TAG, "Floating view added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
            showToast("Overlay permission required.", Toast.LENGTH_LONG)
            stopSelf()
            return
        }

        setupTouch(params)
        setupClick()
        updateRecordButtonVisuals()
    }

    private fun setupTouch(params: WindowManager.LayoutParams) {
        val screenHeight = resources.displayMetrics.heightPixels
        val dismissThreshold = screenHeight * 0.85f

        recordButton.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var isBeingDragged = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        isBeingDragged = false

                        recordButton.isPressed = true
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()

                        if (!isBeingDragged && (dx * dx + dy * dy > 100)) {
                            isBeingDragged = true
                            recordButton.isPressed = false
                        }

                        if (isBeingDragged) {
                            params.x = startX + dx
                            params.y = startY + dy

                            if (params.y + floatingView.height > dismissThreshold) {
                                recordButton.alpha = 0.6f
                                recordButton.imageTintList = ColorStateList.valueOf(Color.RED)
                                recordButton.setImageResource(android.R.drawable.ic_delete)
                            } else {
                                updateRecordButtonVisuals()
                            }

                            try {
                                windowManager.updateViewLayout(floatingView, params)
                            } catch (_: Exception) {}
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        recordButton.isPressed = false

                        if (isBeingDragged) {
                            updateRecordButtonVisuals()

                            if (params.y + floatingView.height > dismissThreshold) {
                                Log.d(TAG, "Button dragged to bottom - dismissing")
                                showToast("🗑️ Floating button removed", Toast.LENGTH_SHORT)
                                stopSelf()
                                return true
                            }

                            snapToEdge(params, floatingView.width, floatingView.height)
                            try {
                                windowManager.updateViewLayout(floatingView, params)
                            } catch (_: Exception) {}
                            return true
                        } else {
                            recordButton.performClick()
                            return true
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        recordButton.isPressed = false
                        recordButton.alpha = 1.0f
                        updateRecordButtonVisuals()
                        return true
                    }
                }
                return false
            }

            private fun snapToEdge(params: WindowManager.LayoutParams, viewWidth: Int, viewHeight: Int) {
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels

                val centerX = params.x + viewWidth / 2

                if (centerX < screenWidth / 2) {
                    params.x = 20
                } else {
                    params.x = screenWidth - viewWidth - 20
                }

                params.y = params.y.coerceIn(0, screenHeight - viewHeight)

                Log.d(TAG, "Snapped to position: (${params.x}, ${params.y})")
            }
        })
    }

    private fun setupClick() {
        recordButton.setOnClickListener {
            Log.d(TAG, "Record button clicked. Recording: $isRecording")

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Record audio permission not granted")
                showToast("Microphone permission required", Toast.LENGTH_LONG)
                return@setOnClickListener
            }

            if (!isRecording) {
                serviceScope.launch {
                    withContext(Dispatchers.Main) {
                        waveformView.setActive(true)
                        waveformView.setAmplitude(0f)
                    }
                    try {
                        audioRecorder.setAmplitudeListener { level ->
                            val normalized = (level * 10f).coerceIn(0f, 1f)
                            mainHandler.post {
                                waveformView.setAmplitude(normalized)
                            }
                        }
                        audioRecorder.startRecording(serviceScope)
                        withContext(Dispatchers.Main) {
                            isRecording = true
                            recordButton.isActivated = true
                            updateRecordButtonVisuals()
                            showToast("🎤 Recording...", Toast.LENGTH_SHORT)
                            Log.d(TAG, "Recording started")
                        }
                        lastRecordingDurationSeconds = 0.0
                    } catch (e: Exception) {
                        audioRecorder.setAmplitudeListener(null)
                        Log.e(TAG, "Failed to start recording", e)
                        withContext(Dispatchers.Main) {
                            waveformView.setActive(false)
                            showToast("Failed to start recording: ${e.message}", Toast.LENGTH_LONG)
                        }
                    }
                }
            } else {
                serviceScope.launch {
                    withContext(Dispatchers.Main) {
                        isRecording = false
                        recordButton.isActivated = false
                        updateRecordButtonVisuals()
                        waveformView.setAmplitude(0f)
                        waveformView.setActive(false)
                        showToast("⏳ Processing...", Toast.LENGTH_SHORT)
                        Log.d(TAG, "Stopping recording and processing...")
                    }

                    var audioData: FloatArray? = null
                    var flacFile: File? = null

                    try {
                        audioData = audioRecorder.stopRecordingAndGetData()

                        if (audioData != null && audioData.isNotEmpty()) {
                            lastRecordingDurationSeconds = audioData.size / 16000.0
                            Log.d(TAG, "Audio data received: ${audioData.size} samples (${audioData.size / 16000.0f}s)")

                            flacFile = saveAudioToFlac(audioData)

                            if (flacFile != null && flacFile.exists() && flacFile.length() > 0) {
                                Log.d(TAG, "Audio saved to FLAC: ${flacFile.length()} bytes")
                                if (DEBUG_SAVE_WAV) {
                                    saveDebugCopyToDownloads(flacFile)
                                }

                                val result = sendToLocalWhisperForTranscription(flacFile)
                                handleTranscriptionResult(result)
                            } else {
                                Log.w(TAG, "Failed to save audio to FLAC or file is empty")
                                withContext(Dispatchers.Main) {
                                    showToast("❌ Failed to save audio", Toast.LENGTH_SHORT)
                                }
                            }
                        } else {
                            Log.w(TAG, "No audio data recorded")
                            lastRecordingDurationSeconds = 0.0
                            waveformView.setActive(false)
                            withContext(Dispatchers.Main) {
                                showToast("❌ No audio recorded", Toast.LENGTH_SHORT)
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.w(TAG, "Processing cancelled")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during processing", e)
                        withContext(Dispatchers.Main) {
                            showToast("❌ Processing error: ${e.message?.take(50)}", Toast.LENGTH_LONG)
                        }
                    } finally {
                        audioRecorder.setAmplitudeListener(null)
                        cleanupTempFiles(flacFile)
                    }
                }
            }
        }
    }

    private suspend fun sendToLocalWhisperForTranscription(audioFile: File): TranscriptionResult {
        return withContext(Dispatchers.IO) {
            val endpoint = "${LocalServerPreferences.getBaseUrl(this@FloatingButtonService)}/transcribe_raw"
            Log.d(TAG, "Sending FLAC to local Whisper: endpoint=$endpoint bytes=${audioFile.length()}")

            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(audioFile.asRequestBody("application/octet-stream".toMediaType()))
                    .header("User-Agent", "SwiftSay-Local-Android/1.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(responseBody) }.getOrElse { JSONObject() }
                    Log.d(TAG, "Local Whisper response code=${response.code} body=${responseBody.take(500)}")

                    if (!response.isSuccessful) {
                        return@withContext TranscriptionResult.Error(
                            "Local Whisper error ${response.code}: ${json.optString("error", response.message)}",
                            response.code,
                            responseBody
                        )
                    }

                    val text = json.optString("text", "").trim()
                    if (text.isBlank()) {
                        return@withContext TranscriptionResult.Empty("Local Whisper returned no speech")
                    }
                    TranscriptionResult.Success(text)
                }
            } catch (e: UnknownHostException) {
                Log.e(TAG, "Cannot resolve local Whisper host", e)
                TranscriptionResult.Error("Cannot resolve the configured Whisper computer")
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Local Whisper request timed out", e)
                TranscriptionResult.Error("Local Whisper request timed out")
            } catch (e: IOException) {
                Log.e(TAG, "Cannot connect to local Whisper", e)
                TranscriptionResult.Error("Cannot connect to local Whisper: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected local Whisper error", e)
                TranscriptionResult.Error("Local Whisper error: ${e.message}")
            }
        }
    }

    private sealed class TranscriptionResult {
        data class Success(val text: String) : TranscriptionResult()
        data class Error(val message: String, val httpCode: Int? = null, val rawBody: String? = null) : TranscriptionResult()
        data class Empty(val reason: String) : TranscriptionResult()
    }

    private suspend fun handleTranscriptionResult(result: TranscriptionResult) {
        when (result) {
            is TranscriptionResult.Success -> {
                Log.d(TAG, "✅ Transcription successful: '${result.text}'")

                val a11yService = TextPasterAccessibilityService.getInstance()
                if (a11yService != null) {
                    Log.d(TAG, "========== DIRECT PASTE ==========")
                    Log.d(TAG, "Sending direct paste with text: '${result.text}'")
                    Log.d(TAG, "Text length: ${result.text.length}")
                    Log.d(TAG, "Text hashcode: ${result.text.hashCode()}")
                    a11yService.pasteTextDirect(result.text)
                    Log.d(TAG, "Direct paste sent")
                    Log.d(TAG, "========================================")
                } else {
                    val broadcastIntent = Intent(TextPasterAccessibilityService.ACTION_PASTE_BROADCAST)
                    broadcastIntent.putExtra(TextPasterAccessibilityService.EXTRA_TEXT_TO_PASTE, result.text)
                    broadcastIntent.setPackage(packageName)

                    Log.d(TAG, "========== SENDING BROADCAST ==========")
                    Log.d(TAG, "About to send broadcast with text: '${result.text}'")
                    Log.d(TAG, "Text length: ${result.text.length}")
                    Log.d(TAG, "Text hashcode: ${result.text.hashCode()}")

                    sendBroadcast(broadcastIntent)

                    Log.d(TAG, "Broadcast sent successfully")
                    Log.d(TAG, "========================================")
                }

                withContext(Dispatchers.Main) {
                    showToast("✅ Transcribed: ${result.text}", Toast.LENGTH_LONG)
                }
            }

            is TranscriptionResult.Error -> {
                Log.e(TAG, "❌ Transcription error: ${result.message}")
                withContext(Dispatchers.Main) {
                    showToast("❌ ${result.message}", Toast.LENGTH_LONG)
                }
            }

            is TranscriptionResult.Empty -> {
                Log.w(TAG, "⚠️ Empty transcription: ${result.reason}")
                withContext(Dispatchers.Main) {
                    showToast("⚠️ No speech detected", Toast.LENGTH_SHORT)
                }
            }
        }
    }

    private fun cleanupTempFiles(vararg files: File?) {
        for (file in files) {
            if (file != null && file.exists()) {
                try {
                    val deleted = file.delete()
                    if (deleted) {
                        Log.d(TAG, "Cleaned up temp file: ${file.name}")
                    } else {
                        Log.w(TAG, "Failed to delete temp file: ${file.name}")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception deleting file: ${file.name}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting file: ${file.name}", e)
                }
            }
        }
    }

    private fun saveAudioToFlac(audioData: FloatArray): File? {
        return try {
            val sampleRate = 16000
            val pcmData = ShortArray(audioData.size)
            for (i in audioData.indices) {
                pcmData[i] = (audioData[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }

            val flacFile = encodePcmToFlac(pcmData, sampleRate, 1)
            if (flacFile != null) {
                Log.d(TAG, "Saved FLAC file: ${flacFile.absolutePath}, size: ${flacFile.length()} bytes")
            }
            if (flacFile == null) {
                Log.e(TAG, "FLAC encoding failed or encoder unavailable")
            }
            flacFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save FLAC file", e)
            null
        }
    }

    private fun writeWavHeader(
        fos: FileOutputStream,
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        fos.write("RIFF".toByteArray())
        fos.write((36 + dataSize).toLittleEndian())
        fos.write("WAVE".toByteArray())

        fos.write("fmt ".toByteArray())
        fos.write(16.toLittleEndian())
        fos.write(1.toShort().toLittleEndian())
        fos.write(channels.toShort().toLittleEndian())
        fos.write(sampleRate.toLittleEndian())
        fos.write(byteRate.toLittleEndian())
        fos.write(blockAlign.toShort().toLittleEndian())
        fos.write(bitsPerSample.toShort().toLittleEndian())

        fos.write("data".toByteArray())
        fos.write(dataSize.toLittleEndian())
    }

    private fun Int.toLittleEndian(): ByteArray {
        return byteArrayOf(
            (this and 0xFF).toByte(),
            ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(),
            ((this shr 24) and 0xFF).toByte()
        )
    }

    private fun Short.toLittleEndian(): ByteArray {
        return byteArrayOf(
            (this.toInt() and 0xFF).toByte(),
            ((this.toInt() shr 8) and 0xFF).toByte()
        )
    }

    private fun encodePcmToFlac(pcmData: ShortArray, sampleRate: Int, channels: Int): File? {
        val format = MediaFormat.createAudioFormat("audio/flac", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
        if (codecName.isNullOrBlank()) {
            Log.e(TAG, "No FLAC encoder available on this device")
            return null
        }

        val outputFile = File.createTempFile("audio_", ".flac", cacheDir)
        outputFile.deleteOnExit()

        val pcmBytes = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmData) {
            pcmBytes.putShort(sample)
        }
        val inputBytes = pcmBytes.array()

        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            FileOutputStream(outputFile).use { output ->
                val bufferInfo = MediaCodec.BufferInfo()
                var inputOffset = 0
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            inputBuffer?.clear()
                            val remaining = inputBytes.size - inputOffset
                            if (remaining > 0 && inputBuffer != null) {
                                val size = minOf(remaining, inputBuffer.remaining())
                                inputBuffer.put(inputBytes, inputOffset, size)
                                inputOffset += size
                                codec.queueInputBuffer(inputIndex, 0, size, 0, 0)
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
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
                                val outBytes = ByteArray(bufferInfo.size)
                                outputBuffer.get(outBytes)
                                output.write(outBytes)
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

    private fun showToast(msg: String, duration: Int = Toast.LENGTH_SHORT) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(applicationContext, msg, duration).show()
        }
    }

    private fun updateRecordButtonVisuals() {
        recordButton.alpha = 1.0f
        recordButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
        if (isRecording) {
            recordButton.setImageDrawable(null)
        } else {
            recordButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        }
    }

    private fun saveDebugCopyToDownloads(source: File) {
        try {
            val extension = source.extension.lowercase().ifEmpty { "flac" }
            val fileName = "swiftsay_${System.currentTimeMillis()}.$extension"
            val mimeType = if (extension == "flac") "audio/flac" else "audio/wav"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SwiftSay")
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Log.w(TAG, "Failed to create MediaStore entry for debug WAV")
                    return
                }
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(source).use { input ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Saved debug WAV to Downloads/SwiftSay/$fileName (uri=$uri)")
            } else {
                val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir == null) {
                    Log.w(TAG, "External downloads dir unavailable; skipping debug WAV save")
                    return
                }
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val target = File(downloadsDir, fileName)
                FileInputStream(source).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Saved debug WAV to ${target.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug WAV", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy called")
        try {
            if (::floatingView.isInitialized) {
                windowManager.removeView(floatingView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view", e)
        }

        serviceScope.cancel("Service destroyed")

        if (isRecording) {
            runBlocking {
                try {
                    audioRecorder.stopRecordingAndGetData()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recording on destroy", e)
                }
            }
        }

        audioRecorder.setAmplitudeListener(null)
        audioRecorder.cleanup()

        Log.d(TAG, "Service cleanup completed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
