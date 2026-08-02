package com.example.earpieceai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import okio.BufferedSink
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.delay
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class FloatingButtonService : Service() {

    companion object {
        private const val TAG = "FloatingButtonService"
        private const val DEEPINFRA_ENDPOINT = "https://api.deepinfra.com/v1/inference/openai/whisper-large-v3"
        private const val DEEPINFRA_LANGUAGE = "en"
        private const val DEEPINFRA_INITIAL_PROMPT = "Hello."
        private const val DEEPINFRA_TEMPERATURE = "0"
        private const val LATEST_RECORDING_FILE_STEM = "latest_recording"
        private const val LOCAL_COMMAND_COOLDOWN_MS = 1500L
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val LONG_COMMAND_DURATION_SAMPLES = (AUDIO_SAMPLE_RATE * 1.2f).toInt()
        private const val SHORT_COMMAND_DURATION_SAMPLES = (AUDIO_SAMPLE_RATE * 0.9f).toInt()
        private const val STREAM_DETECTION_SAFETY_SAMPLES = (AUDIO_SAMPLE_RATE * 0.15f).toInt()
        private const val SPEECH_REWIND_MS = 10_000L
        private const val SPEECH_REWIND_FALLBACK_CHARS_PER_SECOND = 14.0
        private const val VOICE_BRIDGE_CONNECT_TIMEOUT_SECONDS = 10L
        private const val VOICE_BRIDGE_ACK_TIMEOUT_SECONDS = 10L
        private const val VOICE_BRIDGE_RESULT_MAX_WAIT_MS = 180000L
        private const val VOICE_UPLOAD_QUEUE_DIR = "voice_upload_queue"
        private const val VOICE_UPLOAD_RETRY_DELAY_MS = 3_000L
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

    private lateinit var sherpaTtsEngine: SherpaTtsEngine
    private lateinit var sherpaSpeechController: SherpaSpeechController
    private lateinit var googleTtsController: GoogleTtsController
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAssistantResponse: String? = null
    private var voskLocalCommandRecognizer: VoskLocalCommandRecognizer? = null
    private var useAndroidSpeechFallback = false
    private var localCommandRecognizer: SpeechRecognizer? = null
    private var isLocalCommandListening = false
    private var lastLocalCommandAt = 0L
    private var recordingStopTrimSampleCount: Int? = null
    private var localCommandStatus = "Starting local listener..."
    private val isTtsSpeaking = AtomicBoolean(false)
    private var currentSpokenText: String? = null
    private var currentSpeechStartMs = 0L
    private var currentSpeechStartCharOffset = 0
    private var currentSpeechCharIndex = 0
    private val speechProgressPoints = mutableListOf<SpeechProgressPoint>()
    private var pendingVoiceRequestTiming: VoiceRequestTiming? = null
    private var currentSpeechDurationMs = 0L
    private val voiceUploadQueue = ArrayDeque<QueuedVoiceRecording>()
    private val voiceUploadQueueLock = Any()
    private var voiceUploadWorkerJob: Job? = null

    private var isRecording = false
    private var lastRecordingDurationSeconds = 0.0
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(VOICE_BRIDGE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    private data class VoiceBridgeResult(
        val transcription: String,
        val response: String,
        val action: String,
        val serverTimings: ServerTimingBreakdown? = null
    )

    private data class VoiceBridgeAck(
        val requestId: String
    )

    private data class ServerTimingBreakdown(
        val bridgeUploadBodyReadMs: Long? = null,
        val whisperRequestMs: Long? = null,
        val uploadBodyReadMs: Long? = null,
        val transcribeMs: Long? = null,
        val postprocessMs: Long? = null,
        val serverTotalMs: Long? = null,
        val aiMs: Long? = null,
        val totalProcessMs: Long? = null
    )

    private data class VoiceRequestTiming(
        var requestId: String = "",
        val recordingStopStartedMs: Long,
        var audioSamples: Int = 0,
        var audioFormat: String = "unknown",
        var audioBytes: Long = 0L,
        var audioEncodeMs: Long = -1L,
        var uploadAckMs: Long = -1L,
        var serverPushWaitMs: Long = -1L,
        var responseReceivedMs: Long = -1L,
        var ttsStartMs: Long? = null,
        var serverTimings: ServerTimingBreakdown? = null
    )

    private data class LocalBridgeCommand(
        val action: String,
        val chatNumber: Int? = null,
        val direction: String? = null
    )

    private data class QueuedVoiceRecording(
        val audioFile: File,
        val audioFormat: AudioTransportFormat,
        val recordingStopStartedMs: Long,
        val audioSampleCount: Int,
        val audioEncodeMs: Long,
        val queuedAtMs: Long = SystemClock.elapsedRealtime(),
        var attemptCount: Int = 0
    )

    private data class SpeechProgressPoint(
        val elapsedMs: Long,
        val charIndex: Int
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        earpieceaiApi = EarpieceAiApi(this)
        sherpaTtsEngine = SherpaTtsEngine(this)
        sherpaSpeechController = SherpaSpeechController(sherpaTtsEngine)
        googleTtsController = GoogleTtsController(this)
        preloadSelectedSpeechEngine()
        cleanUnservedVoiceUploadsOnStart()
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

        localCommandStatus = "Starting local listener..."
        startForegroundWithMicType(localCommandStatus)

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
        initializeLocalCommandRecognizer()
    }

    private fun startForegroundWithMicType(status: String) {
        val notification = NotificationHelper.createForegroundNotification(this, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.createNotificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NotificationHelper.createNotificationId(), notification)
        }
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
            toggleAiRecording()
        }
    }

    private fun toggleAiRecording() {
        if (isRecording) {
            stopAiRecordingAndProcess()
        } else {
            startAiRecording()
        }
    }

    private fun startAiRecording() {
        if (isRecording) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted")
            showToast("Microphone permission required", Toast.LENGTH_LONG)
            return
        }

        serviceScope.launch {
            recordingStopTrimSampleCount = null
            withContext(Dispatchers.Main) {
                updateLocalCommandStatus("Paused local listener while recording for AI...")
                stopLocalCommandListening()
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
                val streamCommandsEnabled = voskLocalCommandRecognizer?.beginStreamMode() == true
                audioRecorder.setAudioChunkListener { buffer, count ->
                    if (streamCommandsEnabled) {
                        voskLocalCommandRecognizer?.acceptStreamAudio(buffer, count)
                    }
                }
                audioRecorder.startRecording(serviceScope)
                // Refresh token in background while user is speaking.
                launch { earpieceaiApi.getDeepInfraToken() }
                withContext(Dispatchers.Main) {
                    isRecording = true
                    recordButton.isActivated = true
                    updateRecordButtonVisuals()
                    showToast("Recording for AI. Say kilo vesta end to send or kilo vesta cancel to discard.", Toast.LENGTH_SHORT)
                    Log.d(TAG, "Recording started")
                }
                lastRecordingDurationSeconds = 0.0
            } catch (e: Exception) {
                audioRecorder.setAmplitudeListener(null)
                audioRecorder.setAudioChunkListener(null)
                voskLocalCommandRecognizer?.endStreamMode()
                Log.e(TAG, "Failed to start recording", e)
                withContext(Dispatchers.Main) {
                    waveformView.setActive(false)
                    startLocalCommandListening()
                    showToast("Failed to start recording: ${e.message}", Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun stopAiRecordingAndProcess(discardRecording: Boolean = false) {
        if (!isRecording) {
            return
        }
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                isRecording = false
                recordButton.isActivated = false
                updateRecordButtonVisuals()
                waveformView.setAmplitude(0f)
                waveformView.setActive(false)
                if (discardRecording) {
                    showToast("Cancelling AI recording...", Toast.LENGTH_SHORT)
                    Log.d(TAG, "Stopping recording and discarding audio...")
                } else {
                    showToast("Processing AI recording...", Toast.LENGTH_SHORT)
                    Log.d(TAG, "Stopping recording and processing...")
                }
            }

            val recordingStopStartedMs = SystemClock.elapsedRealtime()

            try {
                val audioData = audioRecorder.stopRecordingAndGetData(recordingStopTrimSampleCount)

                if (discardRecording) {
                    val discardedSamples = audioData?.size ?: 0
                    Log.d(TAG, "AI recording cancelled locally; discarded $discardedSamples samples before upload")
                    lastRecordingDurationSeconds = 0.0
                    withContext(Dispatchers.Main) {
                        showToast("AI recording cancelled", Toast.LENGTH_SHORT)
                    }
                    return@launch
                }

                if (audioData != null && audioData.isNotEmpty()) {
                    lastRecordingDurationSeconds = audioData.size / 16000.0
                    Log.d(TAG, "Audio data received: ${audioData.size} samples (${audioData.size / 16000.0f}s)")

                    val audioFormat = AudioTransportPolicy.formatForHost(VoiceBridgePreferences.getHost(this@FloatingButtonService))
                    val encodeStartedAt = SystemClock.elapsedRealtime()
                    val audioFile = saveAudioForTransport(audioData, audioFormat)
                    val audioEncodeMs =
                        (SystemClock.elapsedRealtime() - encodeStartedAt).coerceAtLeast(0L)

                    if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                        Log.d(
                            TAG,
                            "Audio saved as ${audioFormat.name}: ${audioFile.length()} bytes in ${audioEncodeMs}ms"
                        )
                        saveLatestRecordingToDownloads(audioFile, audioFormat)
                        enqueueVoiceRecording(
                            QueuedVoiceRecording(
                                audioFile = audioFile,
                                audioFormat = audioFormat,
                                recordingStopStartedMs = recordingStopStartedMs,
                                audioSampleCount = audioData.size,
                                audioEncodeMs = audioEncodeMs
                            )
                        )
                    } else {
                        Log.w(TAG, "Failed to save audio as ${audioFormat.name} or file is empty")
                        withContext(Dispatchers.Main) {
                            showToast("Failed to save audio", Toast.LENGTH_SHORT)
                        }
                        logVoiceRequestTiming(
                            "failed:audio-encode",
                            VoiceRequestTiming(
                                recordingStopStartedMs = recordingStopStartedMs,
                                audioSamples = audioData.size,
                                audioFormat = audioFormat.name.lowercase(Locale.US),
                                audioEncodeMs = audioEncodeMs,
                                responseReceivedMs =
                                    (SystemClock.elapsedRealtime() - recordingStopStartedMs).coerceAtLeast(0L)
                            )
                        )
                    }
                } else {
                    Log.w(TAG, "No audio data recorded")
                    lastRecordingDurationSeconds = 0.0
                    waveformView.setActive(false)
                    withContext(Dispatchers.Main) {
                        showToast("No audio recorded", Toast.LENGTH_SHORT)
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "Processing cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error during processing", e)
                withContext(Dispatchers.Main) {
                    showToast("Processing error: ${e.message?.take(50)}", Toast.LENGTH_LONG)
                }
            } finally {
                recordingStopTrimSampleCount = null
                audioRecorder.setAmplitudeListener(null)
                audioRecorder.setAudioChunkListener(null)
                voskLocalCommandRecognizer?.endStreamMode()
                withContext(Dispatchers.Main) {
                    startLocalCommandListening()
                }
            }
        }
    }

    private fun initializeLocalCommandRecognizer() {
        voskLocalCommandRecognizer = VoskLocalCommandRecognizer(
            context = applicationContext,
            onCommandText = { text ->
                mainHandler.post { handleLocalCommandText(text) }
            },
            onStreamCommandText = { text, sampleCount ->
                mainHandler.post { handleStreamCommandText(text, sampleCount) }
            },
            onStatus = { status ->
                mainHandler.post { updateLocalCommandStatus(status) }
            },
            onUnavailable = { reason ->
                mainHandler.post {
                    Log.w(TAG, reason)
                    updateLocalCommandStatus("$reason Falling back to Android recognizer.")
                    useAndroidSpeechFallback = true
                    initializeAndroidSpeechRecognizer()
                    startLocalCommandListening()
                }
            }
        )
        voskLocalCommandRecognizer?.initialize()
    }

    private fun initializeAndroidSpeechRecognizer() {
        if (localCommandRecognizer != null) {
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer is not available for local commands")
            updateLocalCommandStatus("Local voice commands unavailable.")
            showToast("Local voice commands are unavailable on this device.", Toast.LENGTH_LONG)
            return
        }

        localCommandRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Local command recognizer ready")
                    updateLocalCommandStatus("Mic active: say kilo vesta begin.")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Local command recognizer detected speech")
                    updateLocalCommandStatus("Heard speech; checking local command...")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Local command recognizer end of speech")
                }

                override fun onError(error: Int) {
                    isLocalCommandListening = false
                    val errorName = speechRecognizerErrorName(error)
                    Log.d(TAG, "Local command recognizer error=$errorName")
                    updateLocalCommandStatus("Local listener restarting: $errorName")
                    scheduleLocalCommandRestart()
                }

                override fun onResults(results: Bundle?) {
                    isLocalCommandListening = false
                    updateLocalCommandStatus("Processing local speech...")
                    handleLocalCommandResults(results)
                    scheduleLocalCommandRestart()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    handleLocalCommandResults(partialResults)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun createLocalCommandIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
    }

    private fun startLocalCommandListening() {
        if (isRecording || isLocalCommandListening) {
            return
        }
        if (!useAndroidSpeechFallback) {
            voskLocalCommandRecognizer?.startListening()
            return
        }
        val recognizer = localCommandRecognizer ?: return
        try {
            recognizer.startListening(createLocalCommandIntent())
            isLocalCommandListening = true
            updateLocalCommandStatus("Listening locally for kilo vesta begin/end/stop.")
            Log.d(TAG, "Started local command listening")
        } catch (e: Exception) {
            isLocalCommandListening = false
            Log.e(TAG, "Failed to start local command listening", e)
            updateLocalCommandStatus("Local listener failed to start: ${e.message?.take(40)}")
            scheduleLocalCommandRestart()
        }
    }

    private fun stopLocalCommandListening() {
        if (!useAndroidSpeechFallback) {
            voskLocalCommandRecognizer?.stopListening()
        }
        isLocalCommandListening = false
        try {
            localCommandRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop local command recognizer", e)
        }
    }

    private fun scheduleLocalCommandRestart() {
        if (isRecording) {
            return
        }
        mainHandler.removeCallbacks(restartLocalCommandRunnable)
        mainHandler.postDelayed(restartLocalCommandRunnable, 350L)
    }

    private val restartLocalCommandRunnable = Runnable {
        startLocalCommandListening()
    }

    private fun handleLocalCommandResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (matches.isEmpty()) {
            return
        }
        val command = matches
            .map { normalizeLocalCommandText(it) }
            .firstOrNull { isLocalCommandPhrase(it) }
            ?: return
        handleNormalizedLocalCommand(command)
    }

    private fun handleLocalCommandText(text: String) {
        val command = normalizeLocalCommandText(text)
        if (!isLocalCommandPhrase(command)) {
            return
        }
        handleNormalizedLocalCommand(command)
    }

    private fun handleStreamCommandText(text: String, detectedSampleCount: Int) {
        val command = normalizeLocalCommandText(text)
        if (!isLocalCommandPhrase(command)) {
            return
        }
        handleNormalizedLocalCommand(command, detectedSampleCount)
    }

    private fun handleNormalizedLocalCommand(command: String, detectedSampleCount: Int? = null) {
        val now = System.currentTimeMillis()
        if (now - lastLocalCommandAt < LOCAL_COMMAND_COOLDOWN_MS) {
            return
        }
        lastLocalCommandAt = now
        Log.d(TAG, "Handling local voice command: $command")
        if (isRecording) {
            when {
                isCancelRecordingCommand(command) -> {
                    stopAiRecordingAndProcess(discardRecording = true)
                }
                isStopSpeakingCommand(command) -> {
                    recordingStopTrimSampleCount = detectedSampleCount?.let {
                        estimateStreamTrimSampleCount(command, it)
                    } ?: estimateRecordingTrimSampleCount(command)
                    stopAiRecordingAndProcess()
                }
                else -> {
                    Log.d(TAG, "Ignoring local command while AI recording is active: $command")
                }
            }
            return
        }
        when {
            isBeginRecordingCommand(command) -> {
                startAiRecording()
                showToast("AI recording started", Toast.LENGTH_SHORT)
            }
            isCreateNewChatCommand(command) -> {
                executeLocalBridgeCommand(
                    LocalBridgeCommand(action = "create_new_chat"),
                    "Creating new chat..."
                )
            }
            isListChatsCommand(command) -> {
                executeLocalBridgeCommand(
                    LocalBridgeCommand(action = "list_chats"),
                    "Listing chats..."
                )
            }
            isSelectChatCommand(command) -> {
                val chatNumber = extractSelectedChatNumber(command)
                if (chatNumber == null) {
                    showToast("Could not tell which chat number to open", Toast.LENGTH_SHORT)
                } else {
                    executeLocalBridgeCommand(
                        LocalBridgeCommand(action = "select_chat", chatNumber = chatNumber),
                        "Opening chat $chatNumber..."
                    )
                }
            }
            isGoBackCommand(command) -> {
                executeLocalBridgeCommand(
                    LocalBridgeCommand(action = "navigate_chat_message", direction = "back"),
                    "Reading previous message..."
                )
            }
            isGoForwardCommand(command) -> {
                executeLocalBridgeCommand(
                    LocalBridgeCommand(action = "navigate_chat_message", direction = "forward"),
                    "Reading next message..."
                )
            }
            isEndRecordingCommand(command) -> {
                stopAiRecordingAndProcess()
            }
            isStopSpeakingCommand(command) -> {
                stopSpeaking()
                showToast("Speech stopped", Toast.LENGTH_SHORT)
            }
            isRewindCommand(command) -> {
                rewindCurrentSpeech()
            }
            isRepeatCommand(command) -> {
                val previous = lastAssistantResponse
                if (previous.isNullOrBlank()) {
                    showToast("No previous response to repeat", Toast.LENGTH_SHORT)
                } else {
                    speakText(previous)
                }
            }
        }
    }

    private fun normalizeLocalCommandText(text: String): String {
        return text
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isLocalCommandPhrase(text: String): Boolean {
        return isBeginRecordingCommand(text) ||
            isCreateNewChatCommand(text) ||
            isListChatsCommand(text) ||
            isSelectChatCommand(text) ||
            isGoBackCommand(text) ||
            isGoForwardCommand(text) ||
            isCancelRecordingCommand(text) ||
            isEndRecordingCommand(text) ||
            isStopSpeakingCommand(text) ||
            isRewindCommand(text) ||
            isRepeatCommand(text)
    }

    private fun isBeginRecordingCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) && words.any { it == "begin" || it == "start" }
    }

    private fun isCreateNewChatCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) &&
            words.contains("chat") &&
            (words.contains("create") || words.contains("new"))
    }

    private fun isListChatsCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) &&
            (words.contains("list") || words.contains("show")) &&
            words.any { it == "chat" || it == "chats" }
    }

    private fun isSelectChatCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) &&
            (words.contains("select") || words.contains("open")) &&
            words.contains("chat") &&
            extractSelectedChatNumber(text) != null
    }

    private fun isGoBackCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) &&
            (words.contains("back") || words.windowed(2).any { it[0] == "go" && it[1] == "back" })
    }

    private fun isGoForwardCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) &&
            (words.contains("forward") || words.windowed(2).any { it[0] == "go" && it[1] == "forward" })
    }

    private fun isEndRecordingCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) && words.any { it == "end" || it == "send" || it == "finish" || it == "done" }
    }

    private fun isCancelRecordingCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) && words.any { it == "cancel" || it == "discard" || it == "abort" }
    }

    private fun isStopSpeakingCommand(text: String): Boolean {
        return hasWakePhrase(text) && text.split(' ').any { it == "stop" }
    }

    private fun isRewindCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) && words.any { it == "rewind" }
    }

    private fun isRepeatCommand(text: String): Boolean {
        val words = text.split(' ')
        return hasWakePhrase(text) && words.any { it == "repeat" || it == "replay" || it == "again" }
    }

    private fun extractSelectedChatNumber(text: String): Int? {
        val normalized = text.split(' ').filter { it.isNotBlank() }
        val chatIndex = normalized.indexOf("chat")
        if (chatIndex == -1 || chatIndex + 1 >= normalized.size) {
            return null
        }
        val candidateTokens = normalized.drop(chatIndex + 1)
            .filterNot { it == "number" || it == "chat" }
        val numberToken = candidateTokens.firstOrNull() ?: return null
        return parseSpokenNumber(numberToken)
    }

    private fun parseSpokenNumber(token: String): Int? {
        return token.toIntOrNull() ?: when (token) {
            "one" -> 1
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            "eight" -> 8
            "nine" -> 9
            "ten" -> 10
            else -> null
        }
    }

    private fun computeSpeechRewindIndex(spokenText: String): Int {
        val elapsedMs = if (currentSpeechStartMs > 0L) {
            (SystemClock.elapsedRealtime() - currentSpeechStartMs).coerceAtLeast(0L)
        } else {
            0L
        }
        if (elapsedMs < SPEECH_REWIND_MS) {
            return fallbackSpeechRewindIndex(spokenText)
        }
        val targetElapsed = (elapsedMs - SPEECH_REWIND_MS).coerceAtLeast(0L)
        synchronized(speechProgressPoints) {
            val point = speechProgressPoints.lastOrNull { it.elapsedMs <= targetElapsed }
            if (point != null) {
                return point.charIndex.coerceIn(0, spokenText.length)
            }
        }
        return fallbackSpeechRewindIndex(spokenText)
    }

    private fun fallbackSpeechRewindIndex(spokenText: String): Int {
        val fallbackChars = ((SPEECH_REWIND_MS / 1000.0) * SPEECH_REWIND_FALLBACK_CHARS_PER_SECOND).toInt()
        return (currentSpeechCharIndex - fallbackChars).coerceIn(0, spokenText.length)
    }

    private fun estimateRecordingTrimSampleCount(command: String): Int {
        val currentSamples = audioRecorder.getSampleCount()
        val trimTailSamples = when {
            command.contains("stop talking") || command.contains("stop speaking") -> LONG_COMMAND_DURATION_SAMPLES
            command.contains("finish") -> LONG_COMMAND_DURATION_SAMPLES
            else -> SHORT_COMMAND_DURATION_SAMPLES
        }
        return (currentSamples - trimTailSamples).coerceAtLeast(0)
    }

    private fun estimateStreamTrimSampleCount(command: String, detectedSampleCount: Int): Int {
        val commandDurationSamples = when {
            command.contains("stop talking") || command.contains("stop speaking") -> LONG_COMMAND_DURATION_SAMPLES
            command.contains("finish") -> LONG_COMMAND_DURATION_SAMPLES
            else -> SHORT_COMMAND_DURATION_SAMPLES
        }
        return (detectedSampleCount - commandDurationSamples - STREAM_DETECTION_SAFETY_SAMPLES).coerceAtLeast(0)
    }

    private fun hasWakePhrase(text: String): Boolean {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.any { it in wakeWordVariants }) {
            return true
        }
        return words.zipWithNext().any { (first, second) ->
            "$first $second" in wakePhraseVariants
        }
    }

    private val wakeWordVariants = setOf(
        "kilovesta",
        "kilofesta",
        "kilofester",
        "kilovestor",
        "keelovesta",
        "keelofesta"
    )

    private val wakePhraseVariants = setOf(
        "kilo vesta",
        "kilo festa",
        "kilo fester",
        "kilo vestor",
        "keelo vesta",
        "keelo festa"
    )

    private fun updateLocalCommandStatus(status: String) {
        localCommandStatus = status
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(
            NotificationHelper.createNotificationId(),
            NotificationHelper.createForegroundNotification(this, status)
        )
    }

    private fun speechRecognizerErrorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio error"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "missing mic permission"
            SpeechRecognizer.ERROR_NETWORK -> "network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no command heard"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
            else -> "error $error"
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

    private fun voiceUploadQueueDir(): File {
        return File(cacheDir, VOICE_UPLOAD_QUEUE_DIR).apply {
            if (!exists() && !mkdirs()) {
                Log.w(TAG, "Failed to create voice upload queue dir: $absolutePath")
            }
        }
    }

    private fun cleanUnservedVoiceUploadsOnStart() {
        val queueDir = voiceUploadQueueDir()
        val files = queueDir.listFiles().orEmpty()
        var deletedCount = 0
        for (file in files) {
            if (!file.isFile) {
                continue
            }
            if (file.delete()) {
                deletedCount++
            } else {
                Log.w(TAG, "Failed to clean unserved voice upload on start: ${file.name}")
            }
        }
        if (deletedCount > 0) {
            Log.d(TAG, "Cleaned $deletedCount unserved voice upload(s) on service start")
        }
    }

    private fun enqueueVoiceRecording(recording: QueuedVoiceRecording) {
        val queueSize = synchronized(voiceUploadQueueLock) {
            voiceUploadQueue.addLast(recording)
            voiceUploadQueue.size
        }
        Log.d(
            TAG,
            "Queued voice recording file=${recording.audioFile.name} bytes=${recording.audioFile.length()} " +
                "samples=${recording.audioSampleCount} queueSize=$queueSize"
        )
        showToast("Queued AI recording ($queueSize pending)", Toast.LENGTH_SHORT)
        startVoiceUploadWorker()
    }

    private fun startVoiceUploadWorker() {
        synchronized(voiceUploadQueueLock) {
            if (voiceUploadWorkerJob?.isActive == true) {
                return
            }
            voiceUploadWorkerJob = serviceScope.launch {
                processVoiceUploadQueue()
            }
        }
    }

    private suspend fun processVoiceUploadQueue() {
        while (currentCoroutineContext().isActive) {
            val recording = synchronized(voiceUploadQueueLock) {
                if (voiceUploadQueue.isEmpty()) null else voiceUploadQueue.removeFirst()
            } ?: break

            serveQueuedVoiceRecording(recording)
        }
        synchronized(voiceUploadQueueLock) {
            if (voiceUploadQueue.isEmpty()) {
                voiceUploadWorkerJob = null
            } else {
                voiceUploadWorkerJob = serviceScope.launch {
                    processVoiceUploadQueue()
                }
            }
        }
    }

    private suspend fun serveQueuedVoiceRecording(recording: QueuedVoiceRecording) {
        while (currentCoroutineContext().isActive) {
            if (!recording.audioFile.exists() || recording.audioFile.length() <= 0L) {
                Log.w(TAG, "Dropping queued voice recording with missing/empty file: ${recording.audioFile.absolutePath}")
                return
            }

            recording.attemptCount++
            try {
                withContext(Dispatchers.Main) {
                    showToast("Sending queued AI recording ${recording.attemptCount}", Toast.LENGTH_SHORT)
                }
                val bridgeResult = sendToVoiceBridge(
                    audioFile = recording.audioFile,
                    audioFormat = recording.audioFormat,
                    recordingStopStartedMs = recording.recordingStopStartedMs,
                    audioSampleCount = recording.audioSampleCount,
                    audioEncodeMs = recording.audioEncodeMs
                )
                withContext(Dispatchers.Main) {
                    showToast("Response received; starting speech", Toast.LENGTH_SHORT)
                    showToast("AI: ${bridgeResult.response}", Toast.LENGTH_LONG)
                }
                handleVoiceBridgeResult(bridgeResult)
                cleanupTempFiles(recording.audioFile)
                return
            } catch (e: CancellationException) {
                Log.w(TAG, "Queued voice upload cancelled; keeping file for startup cleanup: ${recording.audioFile.name}")
                throw e
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Queued voice upload failed; will retry without deleting file=${recording.audioFile.name} " +
                        "attempt=${recording.attemptCount}",
                    e
                )
                withContext(Dispatchers.Main) {
                    showToast("Queued AI upload failed; retrying", Toast.LENGTH_LONG)
                }
                delay(VOICE_UPLOAD_RETRY_DELAY_MS)
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

    private fun saveAudioForTransport(
        audioData: FloatArray,
        audioFormat: AudioTransportFormat
    ): File? {
        return when (audioFormat) {
            AudioTransportFormat.WAV -> saveAudioToWav(audioData)
            AudioTransportFormat.FLAC -> saveAudioToFlac(audioData)
        }
    }

    private fun saveAudioToWav(audioData: FloatArray): File? {
        val outputFile = File.createTempFile("queued_voice_", ".wav", voiceUploadQueueDir())
        return try {
            FileOutputStream(outputFile).use { output ->
                writeWavHeader(output, audioData.size * 2, AUDIO_SAMPLE_RATE, 1, 16)
                val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in audioData) {
                    if (buffer.remaining() < 2) {
                        buffer.flip()
                        while (buffer.hasRemaining()) output.channel.write(buffer)
                        buffer.clear()
                    }
                    buffer.putShort(floatToPcm16(sample))
                }
                buffer.flip()
                while (buffer.hasRemaining()) output.channel.write(buffer)
            }
            outputFile
        } catch (error: Exception) {
            Log.e(TAG, "Failed to save WAV file", error)
            outputFile.delete()
            null
        }
    }

    private fun saveAudioToFlac(audioData: FloatArray): File? {
        return try {
            val flacFile = encodePcmToFlac(audioData, AUDIO_SAMPLE_RATE, 1)
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

    private fun floatToPcm16(sample: Float): Short {
        return (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun encodePcmToFlac(audioData: FloatArray, sampleRate: Int, channels: Int): File? {
        val format = MediaFormat.createAudioFormat("audio/flac", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 0)
        }
        val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
        if (codecName.isNullOrBlank()) {
            Log.e(TAG, "No FLAC encoder available on this device")
            return null
        }

        val outputFile = File.createTempFile("queued_voice_", ".flac", voiceUploadQueueDir())

        var codec: MediaCodec? = null
        try {
            Log.d(TAG, "Using FLAC encoder '$codecName' at compression level 0")
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            FileOutputStream(outputFile).use { output ->
                val bufferInfo = MediaCodec.BufferInfo()
                var inputSampleOffset = 0
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: throw IOException("FLAC encoder input buffer unavailable")
                            inputBuffer.clear()
                            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            val remainingSamples = audioData.size - inputSampleOffset
                            if (remainingSamples > 0) {
                                val sampleCount = minOf(remainingSamples, inputBuffer.remaining() / 2)
                                val presentationTimeUs = inputSampleOffset.toLong() * 1_000_000L /
                                    (sampleRate.toLong() * channels.toLong())
                                repeat(sampleCount) {
                                    inputBuffer.putShort(floatToPcm16(audioData[inputSampleOffset++]))
                                }
                                codec.queueInputBuffer(inputIndex, 0, sampleCount * 2, presentationTimeUs, 0)
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    inputSampleOffset.toLong() * 1_000_000L /
                                        (sampleRate.toLong() * channels.toLong()),
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

    private fun saveLatestRecordingToDownloads(source: File, audioFormat: AudioTransportFormat) {
        try {
            val fileName = "$LATEST_RECORDING_FILE_STEM.${audioFormat.extension}"
            val mimeType = audioFormat.mimeType
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deleteExistingDownloadRecordingsQ()
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EarpieceAI")
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Log.w(TAG, "Failed to create MediaStore entry for latest recording")
                    return
                }
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(source).use { input ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Saved latest recording to Downloads/EarpieceAI/$fileName (uri=$uri)")
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir == null) {
                    Log.w(TAG, "External downloads dir unavailable; skipping latest recording save")
                    return
                }
                val targetDir = File(downloadsDir, "EarpieceAI")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                targetDir.listFiles()?.forEach { existing ->
                    if (existing.isFile && existing.nameWithoutExtension == LATEST_RECORDING_FILE_STEM) {
                        existing.delete()
                    }
                }
                val target = File(targetDir, fileName)
                FileInputStream(source).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Saved latest recording to ${target.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save latest recording", e)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteExistingDownloadRecordingsQ() {
        val resolver = contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/EarpieceAI/"
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf(relativePath, "$LATEST_RECORDING_FILE_STEM.%")
        resolver.delete(collection, selection, selectionArgs)
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

    private fun speakText(text: String, startCharOffset: Int = 0) {
        serviceScope.launch(Dispatchers.Main) {
            val cleanText = sanitizeTextForSpeech(text)
            if (cleanText.isBlank()) {
                Log.d(TAG, "Speech text is empty after sanitization")
                return@launch
            }
            val safeOffset = startCharOffset.coerceIn(0, cleanText.length)
            var playbackOffset = safeOffset
            while (playbackOffset < cleanText.length && cleanText[playbackOffset].isWhitespace()) {
                playbackOffset += 1
            }
            val playbackText = cleanText.substring(playbackOffset)
            if (playbackText.isBlank()) {
                Log.d(TAG, "Speech text is empty after applying offset=$playbackOffset")
                return@launch
            }

            Log.d(TAG, "Speaking clean text from offset=$playbackOffset: $playbackText")
            currentSpokenText = cleanText
            currentSpeechStartCharOffset = playbackOffset
            currentSpeechCharIndex = playbackOffset
            currentSpeechDurationMs = 0L
            synchronized(speechProgressPoints) {
                speechProgressPoints.clear()
                speechProgressPoints.add(SpeechProgressPoint(0L, playbackOffset))
            }
            requestSpeechAudioFocus()
            prepareSpeechAudioRoute()
            when (SpeechEnginePreferences.getSelectedEngine(this@FloatingButtonService)) {
                SpeechEnginePreferences.SpeechEngine.PIPER -> {
                    val voice = SherpaTtsPreferences.getSelectedVoice(this@FloatingButtonService)
                    val speed = SherpaTtsPreferences.getVoiceSpeed(this@FloatingButtonService)
                    sherpaSpeechController.speak(playbackText, voice, speed, object : SherpaSpeechController.Listener {
                        override fun onStart(totalDurationMs: Long) {
                            handleSpeechPlaybackStart(totalDurationMs)
                        }

                        override fun onProgress(positionMs: Long, totalDurationMs: Long, spokenCharIndex: Int) {
                            handleSpeechPlaybackProgress(positionMs, totalDurationMs, spokenCharIndex)
                        }

                        override fun onComplete() {
                            handleSpeechPlaybackComplete()
                        }

                        override fun onError(message: String, throwable: Throwable?) {
                            handleSpeechPlaybackError(message, throwable)
                        }
                    })
                }
                SpeechEnginePreferences.SpeechEngine.GOOGLE -> {
                    val speed = SherpaTtsPreferences.getVoiceSpeed(this@FloatingButtonService)
                    googleTtsController.speak(playbackText, speed, object : GoogleTtsController.Listener {
                        override fun onStart() {
                            handleSpeechPlaybackStart(0L)
                        }

                        override fun onProgress(spokenCharIndex: Int) {
                            val absoluteCharIndex = (currentSpeechStartCharOffset + spokenCharIndex)
                                .coerceIn(0, currentSpokenText?.length ?: 0)
                            currentSpeechCharIndex = absoluteCharIndex
                            synchronized(speechProgressPoints) {
                                val elapsedMs = (SystemClock.elapsedRealtime() - currentSpeechStartMs).coerceAtLeast(0L)
                                val previous = speechProgressPoints.lastOrNull()
                                if (previous == null || previous.charIndex != absoluteCharIndex) {
                                    speechProgressPoints.add(SpeechProgressPoint(elapsedMs, absoluteCharIndex))
                                }
                            }
                        }

                        override fun onComplete() {
                            handleSpeechPlaybackComplete()
                        }

                        override fun onError(message: String, throwable: Throwable?) {
                            handleSpeechPlaybackError(message, throwable)
                        }
                    })
                }
            }
        }
    }

    private fun stopSpeaking() {
        serviceScope.launch(Dispatchers.Main) {
            isTtsSpeaking.set(false)
            sherpaSpeechController.stop()
            googleTtsController.stop()
            abandonSpeechAudioFocus()
            startLocalCommandListening()
        }
    }

    private fun rewindCurrentSpeech() {
        serviceScope.launch(Dispatchers.Main) {
            val spokenText = currentSpokenText
            if (spokenText.isNullOrBlank()) {
                showToast("Nothing to rewind", Toast.LENGTH_SHORT)
                return@launch
            }
            val restartIndex = computeSpeechRewindIndex(spokenText)
            if (spokenText.substring(restartIndex).isBlank()) {
                showToast("Nothing to rewind", Toast.LENGTH_SHORT)
                return@launch
            }
            sherpaSpeechController.stop()
            googleTtsController.stop()
            isTtsSpeaking.set(false)
            speakText(spokenText, restartIndex)
            showToast("Rewound speech by 10 seconds", Toast.LENGTH_SHORT)
        }
    }

    private fun handleSpeechPlaybackStart(totalDurationMs: Long) {
        isTtsSpeaking.set(true)
        currentSpeechStartMs = SystemClock.elapsedRealtime()
        currentSpeechDurationMs = totalDurationMs
        pendingVoiceRequestTiming?.let { timing ->
            timing.ttsStartMs =
                (SystemClock.elapsedRealtime() - timing.recordingStopStartedMs).coerceAtLeast(0L)
            logVoiceRequestTiming("tts-start", timing)
            pendingVoiceRequestTiming = null
        }
        mainHandler.post { startLocalCommandListening() }
    }

    private fun handleSpeechPlaybackProgress(positionMs: Long, totalDurationMs: Long, spokenCharIndex: Int) {
        currentSpeechDurationMs = totalDurationMs
        val absoluteCharIndex = (currentSpeechStartCharOffset + spokenCharIndex)
            .coerceIn(0, currentSpokenText?.length ?: 0)
        currentSpeechCharIndex = absoluteCharIndex
        synchronized(speechProgressPoints) {
            val previous = speechProgressPoints.lastOrNull()
            if (previous == null || previous.charIndex != absoluteCharIndex) {
                speechProgressPoints.add(SpeechProgressPoint(positionMs, absoluteCharIndex))
            }
        }
    }

    private fun handleSpeechPlaybackComplete() {
        isTtsSpeaking.set(false)
        currentSpeechCharIndex = currentSpokenText?.length ?: 0
        abandonSpeechAudioFocus()
        mainHandler.post { startLocalCommandListening() }
    }

    private fun handleSpeechPlaybackError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
        isTtsSpeaking.set(false)
        pendingVoiceRequestTiming = null
        abandonSpeechAudioFocus()
        mainHandler.post { startLocalCommandListening() }
        showToast(message, Toast.LENGTH_LONG)
    }

    private fun executeLocalBridgeCommand(command: LocalBridgeCommand, statusText: String) {
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                showToast(statusText, Toast.LENGTH_SHORT)
            }
            val result = sendLocalBridgeCommand(command)
            withContext(Dispatchers.Main) {
                handleVoiceBridgeResult(result)
            }
        }
    }

    private fun handleVoiceBridgeResult(result: VoiceBridgeResult) {
        when (result.action) {
            "repeat_last_response" -> {
                val previous = lastAssistantResponse
                if (previous.isNullOrBlank()) {
                    speakText("There is no previous assistant response to repeat.")
                } else {
                    speakText(previous)
                }
            }
            "stop_speaking" -> {
                stopSpeaking()
                showToast("Speech stopped", Toast.LENGTH_SHORT)
            }
            "rewind_speaking" -> {
                rewindCurrentSpeech()
            }
            else -> {
                lastAssistantResponse = result.response
                speakText(result.response)
            }
        }
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

    private fun voiceBridgeBaseUrl(): String = VoiceBridgePreferences.getBaseUrl(this)

    private suspend fun sendToVoiceBridge(
        audioFile: File,
        audioFormat: AudioTransportFormat,
        recordingStopStartedMs: Long,
        audioSampleCount: Int,
        audioEncodeMs: Long
    ): VoiceBridgeResult {
        return withContext(Dispatchers.IO) {
            val timing = VoiceRequestTiming(
                recordingStopStartedMs = recordingStopStartedMs,
                audioSamples = audioSampleCount,
                audioFormat = audioFormat.name.lowercase(Locale.US),
                audioBytes = audioFile.length(),
                audioEncodeMs = audioEncodeMs
            )
            val baseUrl = voiceBridgeBaseUrl()
            val requestBody = audioFile.asRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/voice-command")
                .post(requestBody)
                .build()
            try {
                val ack = sendToVoiceBridgeForAck(request, timing)
                val result = waitForVoiceBridgeResultStream(ack.requestId, timing)
                timing.serverTimings = result.serverTimings
                pendingVoiceRequestTiming = timing
                logVoiceRequestTiming("success", timing)
                result
            } catch (error: Exception) {
                timing.responseReceivedMs =
                    (SystemClock.elapsedRealtime() - recordingStopStartedMs).coerceAtLeast(0L)
                logVoiceRequestTiming("failed:${error.javaClass.simpleName}", timing)
                throw error
            }
        }
    }

    private suspend fun sendLocalBridgeCommand(command: LocalBridgeCommand): VoiceBridgeResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("action", command.action)
                    command.chatNumber?.let { put("chat_number", it) }
                    command.direction?.let { put("direction", it) }
                }
                val baseUrl = voiceBridgeBaseUrl()
                val requestBody = RequestBody.create("application/json".toMediaType(), json.toString())
                val request = Request.Builder()
                    .url("$baseUrl/local-command")
                    .post(requestBody)
                    .build()
                val response = httpClient.newCall(request).execute()
                val bodyText = response.body?.string().orEmpty()
                Log.d(TAG, "Local bridge command response: code=${response.code}, body=$bodyText")
                if (!response.isSuccessful) {
                    throw IOException("Local command failed: ${response.code} ${response.message}")
                }
                val resultJson = JSONObject(bodyText)
                VoiceBridgeResult(
                    transcription = resultJson.optString("transcription", ""),
                    response = resultJson.optString("response", bodyText),
                    action = resultJson.optString("action", "speak_response")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed local bridge command", e)
                VoiceBridgeResult(
                    transcription = "",
                    response = "Error running local command: ${e.message}",
                    action = "speak_response",
                    serverTimings = null
                )
            }
        }
    }

    private fun sendToVoiceBridgeForAck(request: Request, timing: VoiceRequestTiming): VoiceBridgeAck {
        val ackClient = httpClient.newBuilder()
            .connectTimeout(VOICE_BRIDGE_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(VOICE_BRIDGE_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(VOICE_BRIDGE_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val ackStartedAt = SystemClock.elapsedRealtime()
        val response = ackClient.newCall(request).execute()
        val bodyText = response.body?.string().orEmpty()
        timing.uploadAckMs = (SystemClock.elapsedRealtime() - ackStartedAt).coerceAtLeast(0L)
        Log.d(TAG, "Voice Bridge ACK response: code=${response.code}, body=$bodyText")
        if (response.code != 202) {
            throw IOException("Voice Bridge did not acknowledge upload: ${response.code} ${response.message}")
        }
        val json = JSONObject(bodyText)
        val requestId = json.optString("request_id", "").trim()
        if (requestId.isBlank()) {
            throw IOException("Voice Bridge ACK missing request id")
        }
        timing.requestId = requestId
        return VoiceBridgeAck(requestId)
    }

    private fun waitForVoiceBridgeResultStream(
        requestId: String,
        timing: VoiceRequestTiming
    ): VoiceBridgeResult {
        val streamClient = httpClient.newBuilder()
            .readTimeout(VOICE_BRIDGE_RESULT_MAX_WAIT_MS, TimeUnit.MILLISECONDS)
            .build()
        val baseUrl = voiceBridgeBaseUrl()
        val request = Request.Builder()
            .url("$baseUrl/voice-command-events/$requestId")
            .get()
            .build()
        val waitStartedAt = SystemClock.elapsedRealtime()
        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Voice Bridge event stream failed: ${response.code} ${response.message}")
            }
            val source = response.body?.source()
                ?: throw IOException("Voice Bridge event stream returned an empty body")
            var currentEvent = "message"
            val dataLines = mutableListOf<String>()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    if (dataLines.isNotEmpty()) {
                        val eventData = dataLines.joinToString("\n")
                        Log.d(TAG, "Voice Bridge SSE event=$currentEvent data=$eventData")
                        when (currentEvent) {
                            "result" -> {
                                timing.serverPushWaitMs =
                                    (SystemClock.elapsedRealtime() - waitStartedAt).coerceAtLeast(0L)
                                timing.responseReceivedMs =
                                    (SystemClock.elapsedRealtime() - timing.recordingStopStartedMs).coerceAtLeast(0L)
                                return parseVoiceBridgeResult(eventData)
                            }
                            "error" -> {
                                val json = JSONObject(eventData)
                                timing.serverPushWaitMs =
                                    (SystemClock.elapsedRealtime() - waitStartedAt).coerceAtLeast(0L)
                                timing.responseReceivedMs =
                                    (SystemClock.elapsedRealtime() - timing.recordingStopStartedMs).coerceAtLeast(0L)
                                timing.serverTimings =
                                    parseServerTimingBreakdown(json.optJSONObject("timings"))
                                throw IOException(json.optString("error", "Voice Bridge processing failed"))
                            }
                        }
                        dataLines.clear()
                        currentEvent = "message"
                    }
                    continue
                }
                if (line.startsWith(":")) {
                    continue
                }
                if (line.startsWith("event:")) {
                    currentEvent = line.substringAfter("event:").trim()
                    continue
                }
                if (line.startsWith("data:")) {
                    dataLines.add(line.substringAfter("data:").trim())
                }
            }
        }
        throw IOException("Voice Bridge event stream closed before sending a result")
    }

    private fun parseVoiceBridgeResult(bodyText: String): VoiceBridgeResult {
        val json = JSONObject(bodyText)
        return VoiceBridgeResult(
            transcription = json.optString("transcription", ""),
            response = json.optString("response", bodyText),
            action = json.optString("action", "speak_response"),
            serverTimings = parseServerTimingBreakdown(json.optJSONObject("timings"))
        )
    }

    private fun parseServerTimingBreakdown(json: JSONObject?): ServerTimingBreakdown? {
        if (json == null) {
            return null
        }
        return ServerTimingBreakdown(
            bridgeUploadBodyReadMs = json.optLongOrNull("bridge_upload_body_read_ms"),
            whisperRequestMs = json.optLongOrNull("whisper_request_ms"),
            uploadBodyReadMs = json.optLongOrNull("upload_body_read_ms"),
            transcribeMs =
                json.optLongOrNull("server_transcribe_ms") ?: json.optLongOrNull("transcribe_ms"),
            postprocessMs = json.optLongOrNull("postprocess_ms"),
            serverTotalMs = json.optLongOrNull("server_total_ms"),
            aiMs = json.optLongOrNull("ai_ms"),
            totalProcessMs = json.optLongOrNull("total_process_ms")
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return optLong(key)
    }

    private fun logVoiceRequestTiming(stage: String, timing: VoiceRequestTiming) {
        val clipSeconds =
            if (timing.audioSamples > 0) timing.audioSamples.toDouble() / AUDIO_SAMPLE_RATE.toDouble() else 0.0
        val server = timing.serverTimings
        val networkResponseOverheadMs =
            if ((server?.whisperRequestMs ?: -1L) >= 0L && (server?.serverTotalMs ?: -1L) >= 0L) {
                (server!!.whisperRequestMs!! - server.serverTotalMs!!).coerceAtLeast(0L)
            } else {
                -1L
            }
        val timingMessage =
            "Voice timing [$stage] requestId=${timing.requestId} " +
                "clip_s=${"%.2f".format(Locale.US, clipSeconds)} " +
                "tail_extract_ms=-1 " +
                "audio_format=${timing.audioFormat} " +
                "audio_bytes=${timing.audioBytes} " +
                "audio_encode_ms=${timing.audioEncodeMs} " +
                "upload_ack_ms=${timing.uploadAckMs} " +
                "bridge_upload_body_read_ms=${server?.bridgeUploadBodyReadMs ?: -1} " +
                "server_push_wait_ms=${timing.serverPushWaitMs} " +
                "response_received_ms=${timing.responseReceivedMs} " +
                "tts_start_ms=${timing.ttsStartMs ?: -1} " +
                "whisper_round_trip_ms=${server?.whisperRequestMs ?: -1} " +
                "upload_body_read_ms=${server?.uploadBodyReadMs ?: -1} " +
                "server_transcribe_ms=${server?.transcribeMs ?: -1} " +
                "server_postprocess_ms=${server?.postprocessMs ?: -1} " +
                "server_total_ms=${server?.serverTotalMs ?: -1} " +
                "network_response_overhead_ms=$networkResponseOverheadMs " +
                "server_ai_ms=${server?.aiMs ?: -1} " +
                "server_total_process_ms=${server?.totalProcessMs ?: -1}"
        Log.d(TAG, timingMessage)
        DebugTimingStore.saveLastTiming(this, timingMessage)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy called")
        usageRetryJob?.cancel()
        usageRetryJob = null
        isTtsSpeaking.set(false)

        mainHandler.removeCallbacks(restartLocalCommandRunnable)
        stopLocalCommandListening()
        voskLocalCommandRecognizer?.shutdown()
        voskLocalCommandRecognizer = null
        try {
            localCommandRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy local command recognizer", e)
        }
        localCommandRecognizer = null

        abandonSpeechAudioFocus()
        sherpaSpeechController.release()
        googleTtsController.release()

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

    private fun preloadSelectedSpeechEngine() {
        when (SpeechEnginePreferences.getSelectedEngine(this)) {
            SpeechEnginePreferences.SpeechEngine.PIPER -> {
                serviceScope.launch {
                    runCatching {
                        sherpaTtsEngine.preload(
                            SherpaTtsPreferences.getSelectedVoice(this@FloatingButtonService),
                            SherpaTtsPreferences.getVoiceSpeed(this@FloatingButtonService)
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Sherpa preload failed in service", error)
                    }
                }
            }
            SpeechEnginePreferences.SpeechEngine.GOOGLE -> {
                googleTtsController.warmUp { result ->
                    result.onSuccess {
                        googleTtsController.applySavedVoice { applyResult ->
                            applyResult.onFailure { error ->
                                Log.w(TAG, "Google TTS applySavedVoice failed in service", error)
                            }
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Google TTS warmup failed in service", error)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
