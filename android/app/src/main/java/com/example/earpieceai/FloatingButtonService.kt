package com.example.earpieceai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
import android.media.ToneGenerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.TimeUnit
import okio.BufferedSink
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.delay
import java.io.FileInputStream
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class FloatingButtonService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FloatingButtonService"
        private const val DEEPINFRA_ENDPOINT = "https://api.deepinfra.com/v1/inference/openai/whisper-large-v3"
        private const val DEEPINFRA_LANGUAGE = "en"
        private const val DEEPINFRA_INITIAL_PROMPT = "Hello."
        private const val DEEPINFRA_TEMPERATURE = "0"
        private const val DEBUG_SAVE_WAV = true
        private const val TTS_UTTERANCE_ID = "voice_bridge_utterance"
        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var recordButton: ImageButton
    private lateinit var waveformView: VoiceWaveformView
    private lateinit var earpieceaiApi: EarpieceAiApi

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioRecorder = AudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var usageRetryJob: Job? = null

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingSpeechText: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var isRecording = false
    private var lastRecordingDurationSeconds = 0.0
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        earpieceaiApi = EarpieceAiApi(this)
        initializeTts()
        startUsageRetryLoop()
        serviceScope.launch {
            flushPendingUsageOnce("service-start")
        }

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
                        // Refresh token in background while user is speaking
                        launch { earpieceaiApi.getDeepInfraToken() }
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

                                withContext(Dispatchers.Main) {
                                    showToast("Sending audio to PC...", Toast.LENGTH_SHORT)
                                }
                                val responseText = sendToVoiceBridge(flacFile)
                                withContext(Dispatchers.Main) {
                                    showToast("Response received; starting speech", Toast.LENGTH_SHORT)
                                    showToast("🤖 AI: $responseText", Toast.LENGTH_LONG)
                                }
                                speakText(responseText)
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

    private suspend fun sendToDeepInfraForTranscription(audioFile: File): TranscriptionResult {
        return withContext(Dispatchers.IO) {
            val tokenResult = earpieceaiApi.getDeepInfraToken()
            val token = tokenResult.getOrNull()
            
            if (token == null) {
                return@withContext TranscriptionResult.Error("API Token not configured. Please login in the app.")
            }

            Log.d(TAG, "Sending audio to DeepInfra, file size: ${audioFile.length()} bytes")
            Log.d(TAG, "Using Token: ${token.take(10)}...${token.takeLast(5)}")

            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("audio", "audio.flac", audioFile.asRequestBody("audio/flac".toMediaType()))
                    .addFormDataPart("language", DEEPINFRA_LANGUAGE)
                    .addFormDataPart("initial_prompt", DEEPINFRA_INITIAL_PROMPT)
                    .addFormDataPart("temperature", DEEPINFRA_TEMPERATURE)
                    .build()

                val request = Request.Builder()
                    .url(DEEPINFRA_ENDPOINT)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("User-Agent", "EarpieceAi-Android/1.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                logLong(
                    TAG,
                    "DeepInfra RAW Response: code=${response.code}, body='${responseBody.orEmpty()}'"
                )

                if (response.isSuccessful) {
                    if (!responseBody.isNullOrEmpty()) {
                        try {
                            val json = JSONObject(responseBody)
                            fun readTextArray(key: String): String {
                                val array = json.optJSONArray(key) ?: return ""
                                val sb = StringBuilder()
                                for (i in 0 until array.length()) {
                                    val item = array.optJSONObject(i) ?: continue
                                    val part = item.optString("text", "").trim()
                                    if (part.isNotEmpty()) {
                                        if (sb.isNotEmpty()) {
                                            sb.append(' ')
                                        }
                                        sb.append(part)
                                    }
                                }
                                return sb.toString().trim()
                            }

                            val segmentsText = readTextArray("segments")
                            val chunksText = readTextArray("chunks")
                            val plainText = json.optString("text", "").trim()
                            val candidates = listOf(
                                "text" to plainText,
                                "segments" to segmentsText,
                                "chunks" to chunksText
                            )
                            val best = candidates
                                .filter { it.second.isNotBlank() }
                                .maxByOrNull { it.second.length }
                            val text = best?.second.orEmpty()
                            val source = best?.first ?: "empty"
                            Log.d(
                                TAG,
                                "Parsed transcription source=$source lengths text=${plainText.length}, segments=${segmentsText.length}, chunks=${chunksText.length}"
                            )

                            if (text.isNotEmpty()) {
                                return@withContext TranscriptionResult.Success(text)
                            } else {
                                Log.w(TAG, "Parsed JSON but text was empty. JSON: $json")
                                return@withContext TranscriptionResult.Empty("DeepInfra returned empty text")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse JSON: $responseBody", e)
                            return@withContext TranscriptionResult.Error("Failed to parse API response")
                        }
                    } else {
                        return@withContext TranscriptionResult.Error("Empty response body from API")
                    }
                } else {
                    val errorMsg = "API error ${response.code}: ${responseBody ?: "empty body"}"
                    Log.e(TAG, errorMsg)
                    if (response.code == 401) {
                        return@withContext TranscriptionResult.Error(
                            message = "Invalid API Token (401)",
                            httpCode = response.code,
                            rawBody = responseBody
                        )
                    }
                    return@withContext TranscriptionResult.Error(
                        message = errorMsg,
                        httpCode = response.code,
                        rawBody = responseBody
                    )
                }

            } catch (e: UnknownHostException) {
                Log.e(TAG, "Cannot reach DeepInfra", e)
                return@withContext TranscriptionResult.Error("Cannot connect to DeepInfra API")
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "API timeout", e)
                return@withContext TranscriptionResult.Error("API timeout - check your connection")
            } catch (e: IOException) {
                Log.e(TAG, "Network error", e)
                return@withContext TranscriptionResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                return@withContext TranscriptionResult.Error("Unexpected error: ${e.message}")
            }
        }
    }

    private fun ByteArray.toRequestBody(contentType: MediaType): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType = contentType

            override fun writeTo(sink: BufferedSink) {
                sink.write(this@toRequestBody)
            }

            override fun contentLength(): Long = this@toRequestBody.size.toLong()
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

    private suspend fun resolveDeepInfraError(
        error: TranscriptionResult.Error,
        audioFile: File
    ): TranscriptionResult {
        val code = error.httpCode ?: return error
        val body = error.rawBody?.lowercase().orEmpty()

        return when {
            code == 402 && body.contains("spending limit") -> {
                Log.w(TAG, "DeepInfra 402 body: ${error.rawBody}")
                Log.d(TAG, "Spending limit exceeded, refreshing token...")
                val tokenResult = earpieceaiApi.getDeepInfraToken(forceRefresh = true)
                if (tokenResult.isSuccess) {
                    val retry = sendToDeepInfraForTranscription(audioFile)
                    if (retry is TranscriptionResult.Error && retry.httpCode == 402) {
                        Log.w(TAG, "DeepInfra 402 retry body: ${retry.rawBody}")
                        TranscriptionResult.Error(
                            "Usage token exhausted — please try again shortly.",
                            retry.httpCode,
                            retry.rawBody
                        )
                    } else {
                        retry
                    }
                } else {
                    TranscriptionResult.Error(
                        "Usage token exhausted — please try again shortly.",
                        code,
                        error.rawBody
                    )
                }
            }
            code == 401 && (body.contains("invalid") || body.contains("expired") || body.isEmpty()) -> {
                Log.w(TAG, "DeepInfra 401 body: ${error.rawBody}")
                Log.d(TAG, "Token invalid/expired, refreshing...")
                val tokenResult = earpieceaiApi.getDeepInfraToken(forceRefresh = true)
                if (tokenResult.isSuccess) {
                    val retry = sendToDeepInfraForTranscription(audioFile)
                    if (retry is TranscriptionResult.Error && retry.httpCode == 401) {
                        Log.w(TAG, "DeepInfra 401 retry body: ${retry.rawBody}")
                        TranscriptionResult.Error(
                            "Session expired — please log in again.",
                            retry.httpCode,
                            retry.rawBody
                        )
                    } else {
                        retry
                    }
                } else {
                    TranscriptionResult.Error(
                        "Session expired — please log in again.",
                        code,
                        error.rawBody
                    )
                }
            }
            code == 429 -> {
                var attempt = 0
                var latest: TranscriptionResult = error
                while (attempt < 3) {
                    val delayMs = (1 shl attempt) * 1000L
                    delay(delayMs)
                    latest = sendToDeepInfraForTranscription(audioFile)
                    if (latest !is TranscriptionResult.Error || latest.httpCode != 429) {
                        return latest
                    }
                    attempt++
                }
                TranscriptionResult.Error(
                    "Rate limit exceeded. Please try again in a moment.",
                    code,
                    error.rawBody
                )
            }
            code == 413 -> {
                TranscriptionResult.Error(
                    "Recording too large. Please record a shorter clip.",
                    code,
                    error.rawBody
                )
            }
            code == 400 -> {
                Log.w(TAG, "DeepInfra bad request: ${error.rawBody}")
                TranscriptionResult.Error(
                    "Invalid audio format or model. Please try again.",
                    code,
                    error.rawBody
                )
            }
            code in 500..504 -> {
                TranscriptionResult.Error(
                    "Local Engine is under heavy load. Please try again in a moment.",
                    code,
                    error.rawBody
                )
            }
            code == 403 -> {
                TranscriptionResult.Error(
                    "Model not allowed for this token. Please update the app.",
                    code,
                    error.rawBody
                )
            }
            else -> error
        }
    }

    private fun startUsageRetryLoop() {
        if (usageRetryJob != null) {
            return
        }
        usageRetryJob = serviceScope.launch {
            while (isActive) {
                delay(60_000)
                flushPendingUsageOnce("retry-loop")
            }
        }
    }

    private suspend fun flushPendingUsageOnce(source: String) {
        if (!earpieceaiApi.isLoggedIn()) {
            Log.w(TAG, "Usage flush skipped ($source): not logged in")
            return
        }
        Log.d(TAG, "Attempting usage flush ($source)")
        val result = earpieceaiApi.flushPendingUsage()
        if (result.isSuccess) {
            Log.d(TAG, "Usage flush success ($source)")
            return
        }
        val error = result.exceptionOrNull()
        if (error is UnauthorizedException) {
            Log.w(TAG, "Usage flush unauthorized ($source)")
            earpieceaiApi.logout()
            withContext(Dispatchers.Main) {
                showToast("Session expired — please log in again.", Toast.LENGTH_LONG)
            }
            stopSelf()
        } else {
            Log.w(TAG, "Usage flush failed ($source)", error)
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
            val fileName = "earpieceai_${System.currentTimeMillis()}.$extension"
            val mimeType = if (extension == "flac") "audio/flac" else "audio/wav"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EarpieceAI")
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
                Log.d(TAG, "Saved debug WAV to Downloads/EarpieceAI/$fileName (uri=$uri)")
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

    private fun logLong(tag: String, message: String) {
        val maxLen = 3500
        if (message.length <= maxLen) {
            Log.d(tag, message)
            return
        }
        var index = 0
        var part = 0
        while (index < message.length) {
            val end = (index + maxLen).coerceAtMost(message.length)
            Log.d(tag, "part ${part + 1}: ${message.substring(index, end)}")
            index = end
            part += 1
        }
    }

    private fun countWords(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return 0
        }
        return trimmed.split(Regex("\\s+")).size
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            if (!configureTtsEngine()) {
                Log.e(TAG, "TTS engine initialized but could not be configured")
                showToast("TTS language/audio setup failed", Toast.LENGTH_LONG)
                return
            }

            isTtsInitialized = true
            Log.d(TAG, "TTS initialized successfully")

            val pendingText = pendingSpeechText
            if (pendingText != null) {
                pendingSpeechText = null
                Log.d(TAG, "Speaking pending text: $pendingText")
                if (!speakPreparedText(pendingText)) {
                    resetTtsAndQueue(pendingText)
                }
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
            showToast("TTS initialization failed", Toast.LENGTH_LONG)
        }
    }

    private fun initializeTts() {
        isTtsInitialized = false
        tts = TextToSpeech(this, this, GOOGLE_TTS_ENGINE)
    }

    private fun configureTtsEngine(): Boolean {
        val engine = tts ?: return false
        val languageResult = engine.setLanguage(Locale.getDefault())
        Log.d(TAG, "TTS setLanguage(${Locale.getDefault()}) result=$languageResult")
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Default TTS language unsupported; falling back to US English")
            val fallbackResult = engine.setLanguage(Locale.US)
            Log.d(TAG, "TTS setLanguage(${Locale.US}) fallback result=$fallbackResult")
            if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "US English TTS language unavailable")
                showToast("TTS voice data missing; open app and use Test Phone Speech.", Toast.LENGTH_LONG)
                return false
            }
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS finished: $utteranceId")
                abandonSpeechAudioFocus()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS playback error: $utteranceId")
                abandonSpeechAudioFocus()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS playback error: $utteranceId, code=$errorCode")
                abandonSpeechAudioFocus()
                showToast("TTS playback failed: $errorCode", Toast.LENGTH_LONG)
            }
        })
        return true
    }

    private fun sanitizeTextForSpeech(text: String): String {
        var clean = text

        // 1. Remove URLs and links: [Label](url) -> Label
        clean = clean.replace(Regex("\\[(.*?)\\]\\(https?://.*?\\)"), "$1")

        // 2. Remove standalone URLs: https://google.com
        clean = clean.replace(Regex("https?://\\S+"), "")

        // 3. Remove citations: [1], [1, 2], [1-5]
        clean = clean.replace(Regex("\\[\\d+(?:[\\s,-]*\\d+)*\\]"), "")

        // 4. Remove Markdown formatting characters: *, #, _, `
        clean = clean.replace(Regex("[*#_`]"), "")

        // 5. Clean up multiple spaces, empty lines, and trailing tabs/newlines
        clean = clean.replace(Regex("\\s+"), " ").trim()

        return clean
    }

    private fun speakText(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            val cleanText = sanitizeTextForSpeech(text)
            if (cleanText.isBlank()) {
                Log.d(TAG, "Speech text is empty after sanitization")
                return@launch
            }

            if (tts == null) {
                Log.d(TAG, "TTS is null, initializing on-demand and queueing text...")
                pendingSpeechText = cleanText
                initializeTts()
                return@launch
            }

            if (!isTtsInitialized) {
                Log.d(TAG, "TTS is initializing, queueing text...")
                pendingSpeechText = cleanText
                return@launch
            }

            Log.d(TAG, "Speaking clean text: $cleanText")
            if (!speakPreparedText(cleanText)) {
                resetTtsAndQueue(cleanText)
            }
        }
    }

    private fun speakPreparedText(cleanText: String): Boolean {
        requestSpeechAudioFocus()
        prepareSpeechAudioRoute()
        playSpeechRouteProbeTone()
        val speakResult = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
        if (speakResult == TextToSpeech.SUCCESS) {
            return true
        }
        Log.e(TAG, "tts.speak failed with result=$speakResult")
        abandonSpeechAudioFocus()
        return false
    }

    private fun requestSpeechAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    Log.d(TAG, "Speech audio focus changed: $change")
                }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        Log.d(TAG, "Speech audio focus request result: $result")
    }

    private fun prepareSpeechAudioRoute() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val route = describeSpeechOutputRoute(audioManager)
        val mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }

        if (!route.hasExternalOutput) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }

        Log.d(
            TAG,
            "Speech route=${route.description}, mediaVolume=$mediaVolume/$mediaMaxVolume, speakerphone=${audioManager.isSpeakerphoneOn}"
        )
        if (mediaVolume == 0) {
            showToast("Media volume is muted; raise phone media volume.", Toast.LENGTH_LONG)
        }
    }

    private data class SpeechOutputRoute(
        val description: String,
        val hasExternalOutput: Boolean
    )

    private fun describeSpeechOutputRoute(audioManager: AudioManager): SpeechOutputRoute {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val hasExternal = audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
            return SpeechOutputRoute(
                if (hasExternal) "legacy external output" else "legacy phone speaker",
                hasExternal
            )
        }

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val externalTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        val external = outputs.firstOrNull { it.type in externalTypes }
        val description = outputs.joinToString(", ") { device ->
            "${audioDeviceTypeName(device.type)}:${device.productName}"
        }.ifBlank { "no output devices reported" }

        return SpeechOutputRoute(description, external != null)
    }

    private fun audioDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
            else -> "type-$type"
        }
    }

    private fun abandonSpeechAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun playSpeechRouteProbeTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            mainHandler.postDelayed({ tone.release() }, 300)
        } catch (e: Exception) {
            Log.w(TAG, "Speech route probe tone failed", e)
        }
    }

    private fun resetTtsAndQueue(cleanText: String) {
        Log.e(TAG, "Resetting TTS and queueing response")
        pendingSpeechText = cleanText
        isTtsInitialized = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to shut down previous TTS instance", e)
        }
        initializeTts()
    }

    private suspend fun sendToVoiceBridge(audioFile: File): String {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = audioFile.asRequestBody("application/octet-stream".toMediaType())
                val request = Request.Builder()
                    .url("http://192.168.50.51:9090/voice-command")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val bodyText = response.body?.string().orEmpty()
                Log.d(TAG, "Voice Bridge response: $bodyText")
                if (response.isSuccessful) {
                    // Since the standalone voice_bridge.js returns JSON now (e.g. {"transcription": "...", "response": "..."})
                    // let's parse the JSON to get the text response to speak!
                    try {
                        val json = JSONObject(bodyText)
                        json.optString("response", bodyText)
                    } catch (e: Exception) {
                        bodyText
                    }
                } else {
                    "Error: ${response.code} ${response.message}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Voice Bridge", e)
                "Error connecting to PC server: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy called")
        usageRetryJob?.cancel()
        usageRetryJob = null

        abandonSpeechAudioFocus()
        tts?.stop()
        tts?.shutdown()

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
