package com.example.earpieceai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var startServiceButton: Button
    private lateinit var stopServiceButton: Button
    private lateinit var testSpeechButton: Button
    private lateinit var enableAccessibilityButton: Button
    private lateinit var configButton: Button
    private lateinit var profileButton: Button
    private lateinit var voiceSettingsButton: Button
    private lateinit var voiceSpeedButton: Button
    private lateinit var speechEngineButton: Button
    private lateinit var serverSettingsButton: Button
    private lateinit var selectAudioButton: Button
    private lateinit var selectLatestRecordingButton: Button
    private lateinit var sendImportedAudioButton: Button
    private lateinit var selectedVoiceValue: TextView
    private lateinit var selectedVoiceSpeedValue: TextView
    private lateinit var selectedEngineValue: TextView
    private lateinit var serverAddressValue: TextView
    private lateinit var selectedAudioValue: TextView
    private lateinit var importedTailSecondsInput: EditText
    private lateinit var importedResultValue: TextView
    private lateinit var debugTimingValue: TextView
    
    private lateinit var btnStep1: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnMic: Button
    
    private lateinit var step1Title: TextView
    private lateinit var step2Title: TextView
    private lateinit var step3Title: TextView
    private lateinit var step4Title: TextView
    private lateinit var earpieceaiApi: EarpieceAiApi
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var usageRetryJob: kotlinx.coroutines.Job? = null
    private var debugRefreshJob: kotlinx.coroutines.Job? = null
    private lateinit var sherpaTtsEngine: SherpaTtsEngine
    private lateinit var sherpaSpeechController: SherpaSpeechController
    private lateinit var googleTtsController: GoogleTtsController
    private var selectedImportedAudioUri: Uri? = null
    private val selectImportedAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist read permission for imported audio URI", e)
            }
            selectedImportedAudioUri = uri
            val displayName = resolveImportedAudioDisplayName(uri)
            ImportedAudioPreferences.saveSelectedAudio(this, uri.toString(), displayName)
            updateImportedAudioSummary()
        }
    private val requestAudioLibraryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                chooseLatestRecording()
            } else {
                Toast.makeText(
                    this,
                    "Audio permission is required to find the latest recording",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        earpieceaiApi = EarpieceAiApi(this)
        sherpaTtsEngine = SherpaTtsEngine(this)
        sherpaSpeechController = SherpaSpeechController(sherpaTtsEngine)
        googleTtsController = GoogleTtsController(this)

        setContentView(R.layout.activity_main)

        startServiceButton = findViewById(R.id.start_service_button)
        stopServiceButton = findViewById(R.id.stop_service_button)
        testSpeechButton = findViewById(R.id.test_speech_button)
        enableAccessibilityButton = findViewById(R.id.enable_accessibility_button)
        configButton = findViewById(R.id.config_button)
        profileButton = findViewById(R.id.profile_button)
        voiceSettingsButton = findViewById(R.id.voice_settings_button)
        voiceSpeedButton = findViewById(R.id.voice_speed_button)
        speechEngineButton = findViewById(R.id.speech_engine_button)
        serverSettingsButton = findViewById(R.id.server_settings_button)
        selectAudioButton = findViewById(R.id.select_audio_button)
        selectLatestRecordingButton = findViewById(R.id.select_latest_recording_button)
        sendImportedAudioButton = findViewById(R.id.send_imported_audio_button)
        selectedVoiceValue = findViewById(R.id.selected_voice_value)
        selectedVoiceSpeedValue = findViewById(R.id.selected_voice_speed_value)
        selectedEngineValue = findViewById(R.id.selected_engine_value)
        serverAddressValue = findViewById(R.id.server_address_value)
        selectedAudioValue = findViewById(R.id.selected_audio_value)
        importedTailSecondsInput = findViewById(R.id.imported_tail_seconds_input)
        importedResultValue = findViewById(R.id.imported_result_value)
        debugTimingValue = findViewById(R.id.debug_timing_value)
        
        // Hide profile and config buttons for the standalone local version
        profileButton.visibility = android.view.View.GONE
        configButton.visibility = android.view.View.GONE
        
        btnStep1 = findViewById(R.id.btn_step1)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnMic = findViewById(R.id.btn_mic)
        
        step1Title = findViewById(R.id.step1_title)
        step2Title = findViewById(R.id.step2_title)
        step3Title = findViewById(R.id.step3_title)
        step4Title = findViewById(R.id.step4_title)

        btnStep1.setOnClickListener { openAppInfo() }
        enableAccessibilityButton.setOnClickListener { openAccessibilitySettings() }
        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnMic.setOnClickListener { requestAudioPermission() }
        
        startServiceButton.setOnClickListener { checkAndStartFloatingService() }
        stopServiceButton.setOnClickListener { stopFloatingService() }
        testSpeechButton.setOnClickListener { testPhoneSpeech() }
        speechEngineButton.setOnClickListener { openSpeechEngineDialog() }
        voiceSettingsButton.setOnClickListener { openVoicePicker() }
        voiceSpeedButton.setOnClickListener { openVoiceSpeedDialog() }
        serverSettingsButton.setOnClickListener { openServerSettingsDialog() }
        selectAudioButton.setOnClickListener { selectImportedAudioFile() }
        selectLatestRecordingButton.setOnClickListener { chooseLatestRecordingWithPermission() }
        sendImportedAudioButton.setOnClickListener { sendImportedAudioTail() }
        profileButton.setOnClickListener { openProfile() }
        configButton.setOnClickListener { handleLoginOrLogout() }
        restoreImportedAudioState()
        preloadSelectedSpeechEngine()
        updateSelectedVoiceSummary()
        updateServerAddressSummary()
        refreshDebugTimingPanel()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        updateSelectedVoiceSummary()
        updateServerAddressSummary()
        refreshDebugTimingPanel()
        startDebugTimingRefreshLoop()
    }

    override fun onPause() {
        super.onPause()
        debugRefreshJob?.cancel()
        debugRefreshJob = null
    }

    private fun updateUiState() {
        val overlayGranted = checkOverlayPermission()
        val audioGranted = checkAudioPermission()
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        // Step 1: Restricted Settings (Instructional)
        step1Title.text = "Step 1: Allow Restricted Settings " + if (Build.VERSION.SDK_INT >= 33) "ℹ️" else "✅"
        
        // Step 2: Accessibility
        if (accessibilityEnabled) {
            step2Title.text = "Step 2: Enable Earpiece AI Service ✅"
            enableAccessibilityButton.isEnabled = false
            enableAccessibilityButton.text = "Enabled"
        } else {
            step2Title.text = "Step 2: Enable Earpiece AI Service ❌"
            enableAccessibilityButton.isEnabled = true
            enableAccessibilityButton.text = "Enable Accessibility"
        }

        // Step 3: Overlay
        if (overlayGranted) {
            step3Title.text = "Step 3: Display Over Other Apps ✅"
            btnOverlay.isEnabled = false
            btnOverlay.text = "Granted"
        } else {
            step3Title.text = "Step 3: Display Over Other Apps ❌"
            btnOverlay.isEnabled = true
            btnOverlay.text = "Grant Overlay Permission"
        }

        // Step 4: Microphone
        if (audioGranted) {
            step4Title.text = "Step 4: Microphone Access ✅"
            btnMic.isEnabled = false
            btnMic.text = "Granted"
        } else {
            step4Title.text = "Step 4: Microphone Access ❌"
            btnMic.isEnabled = true
            btnMic.text = "Grant Mic Permission"
        }

        // Start Button State
        startServiceButton.isEnabled = overlayGranted && audioGranted
        startServiceButton.alpha = if (startServiceButton.isEnabled) 1.0f else 0.55f
    }

    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
        Toast.makeText(this, "Click the 3 dots (top right) -> Allow restricted settings", Toast.LENGTH_LONG).show()
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        if (!checkAudioPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 102)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateUiState()
    }

    private fun checkAndStartFloatingService() {
        if (!checkOverlayPermission() || !checkAudioPermission()) {
            Toast.makeText(this, "Please complete all steps first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Not Enabled")
                .setMessage("Auto-paste will not work. Start anyway?")
                .setPositiveButton("Start") { _, _ -> startFloatingService() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingButtonService::class.java)
        stopService(intent)
    }

    private fun testPhoneSpeech() {
        val message = "Speech test from Earpiece AI."
        Toast.makeText(this, "Testing phone speaker via media volume", Toast.LENGTH_SHORT).show()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (volume == 0) {
            Toast.makeText(this, "Media volume is muted", Toast.LENGTH_LONG).show()
        }

        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone ->
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                android.os.Handler(mainLooper).postDelayed({ tone.release() }, 300)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Test beep failed", e)
        }

        when (SpeechEnginePreferences.getSelectedEngine(this)) {
            SpeechEnginePreferences.SpeechEngine.PIPER -> {
                val voice = SherpaTtsPreferences.getSelectedVoice(this)
                val speed = SherpaTtsPreferences.getVoiceSpeed(this)
                sherpaSpeechController.speak(message, voice, speed, object : SherpaSpeechController.Listener {
                    override fun onStart(totalDurationMs: Long) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Piper playback started",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onProgress(positionMs: Long, totalDurationMs: Long, spokenCharIndex: Int) = Unit

                    override fun onComplete() = Unit

                    override fun onError(message: String, throwable: Throwable?) {
                        Log.e(TAG, message, throwable)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
            SpeechEnginePreferences.SpeechEngine.GOOGLE -> {
                googleTtsController.speak(message, SherpaTtsPreferences.getVoiceSpeed(this), object : GoogleTtsController.Listener {
                    override fun onStart() {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Google TTS playback started",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onProgress(spokenCharIndex: Int) = Unit

                    override fun onComplete() = Unit

                    override fun onError(message: String, throwable: Throwable?) {
                        Log.e(TAG, message, throwable)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
        }
    }

    private fun openSpeechEngineDialog() {
        val engines = SpeechEnginePreferences.SpeechEngine.entries
        val currentEngine = SpeechEnginePreferences.getSelectedEngine(this)
        val checkedIndex = engines.indexOf(currentEngine).takeIf { it >= 0 } ?: 0
        val labels = engines.map { it.label }.toTypedArray()
        var selectedIndex = checkedIndex

        AlertDialog.Builder(this)
            .setTitle("Speech engine")
            .setSingleChoiceItems(labels, checkedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Save") { _, _ ->
                val engine = engines[selectedIndex]
                SpeechEnginePreferences.saveSelectedEngine(this, engine)
                preloadSelectedSpeechEngine()
                updateSelectedVoiceSummary()
                Toast.makeText(this, "${engine.label} selected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVoicePicker() {
        when (SpeechEnginePreferences.getSelectedEngine(this)) {
            SpeechEnginePreferences.SpeechEngine.PIPER -> showSherpaVoicePickerDialog()
            SpeechEnginePreferences.SpeechEngine.GOOGLE -> showGoogleVoicePickerDialog()
        }
    }

    private fun showSherpaVoicePickerDialog() {
        val voiceOptions = SherpaVoiceCatalog.voices
        val checkedIndex = voiceOptions.indexOfFirst {
            it.id == SherpaTtsPreferences.getSelectedVoiceId(this)
        }.takeIf { it >= 0 } ?: 0
        val labels = voiceOptions.map { it.label }.toTypedArray()
        var selectedIndex = checkedIndex

        AlertDialog.Builder(this)
            .setTitle("Piper voice")
            .setSingleChoiceItems(labels, checkedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Save") { _, _ ->
                val option = voiceOptions[selectedIndex]
                SherpaTtsPreferences.saveSelectedVoiceId(this, option.id)
                sherpaTtsEngine.release()
                coroutineScope.launch(Dispatchers.Default) {
                    runCatching {
                        sherpaTtsEngine.preload(option, SherpaTtsPreferences.getVoiceSpeed(this@MainActivity))
                    }.onFailure { error ->
                        Log.w(TAG, "Sherpa preload failed after voice change", error)
                    }
                }
                updateSelectedVoiceSummary(option.label)
                Toast.makeText(this, "Piper voice updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGoogleVoicePickerDialog() {
        googleTtsController.getVoiceOptions { result ->
            runOnUiThread {
                result.onSuccess { voiceOptions ->
                    val currentVoiceName = TtsVoicePreferences.getSelectedVoiceName(this)
                    val checkedIndex = voiceOptions.indexOfFirst { it.voiceName == currentVoiceName }
                        .takeIf { it >= 0 } ?: 0
                    val labels = voiceOptions.map { it.label }.toTypedArray()
                    var selectedIndex = checkedIndex

                    AlertDialog.Builder(this)
                        .setTitle("Google TTS voice")
                        .setSingleChoiceItems(labels, checkedIndex) { _, which ->
                            selectedIndex = which
                        }
                        .setPositiveButton("Save") { _, _ ->
                            val option = voiceOptions[selectedIndex]
                            TtsVoicePreferences.saveSelectedVoiceName(this, option.voiceName)
                            googleTtsController.applySavedVoice { applyResult ->
                                runOnUiThread {
                                    applyResult.onSuccess { label ->
                                        updateSelectedVoiceSummary(label)
                                        Toast.makeText(this, "Google TTS voice updated", Toast.LENGTH_SHORT).show()
                                    }.onFailure { error ->
                                        Log.e(TAG, "Failed to apply Google TTS voice", error)
                                        Toast.makeText(this, "Google TTS voice update failed", Toast.LENGTH_LONG).show()
                                        updateSelectedVoiceSummary()
                                    }
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to load Google TTS voices", error)
                    Toast.makeText(
                        this,
                        "Google TTS voices unavailable: ${error.message ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openVoiceSpeedDialog() {
        val speedOptions = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.35f, 1.5f)
        val currentSpeed = SherpaTtsPreferences.getVoiceSpeed(this)
        val checkedIndex = speedOptions.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        val labels = speedOptions.map { formatVoiceSpeed(it) }.toTypedArray()
        var selectedIndex = checkedIndex

        AlertDialog.Builder(this)
            .setTitle("Assistant voice speed")
            .setSingleChoiceItems(labels, checkedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Save") { _, _ ->
                val speed = speedOptions[selectedIndex]
                SherpaTtsPreferences.saveVoiceSpeed(this, speed)
                sherpaTtsEngine.release()
                coroutineScope.launch(Dispatchers.Default) {
                    runCatching {
                        sherpaTtsEngine.preload(SherpaTtsPreferences.getSelectedVoice(this@MainActivity), speed)
                    }.onFailure { error ->
                        Log.w(TAG, "Sherpa preload failed after speed change", error)
                    }
                }
                updateSelectedVoiceSummary()
                preloadSelectedSpeechEngine()
                Toast.makeText(this, "Voice speed updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSelectedVoiceSummary(overrideLabel: String? = null) {
        val engine = SpeechEnginePreferences.getSelectedEngine(this)
        val label = overrideLabel ?: when (engine) {
            SpeechEnginePreferences.SpeechEngine.PIPER -> SherpaTtsPreferences.getSelectedVoice(this).label
            SpeechEnginePreferences.SpeechEngine.GOOGLE -> "Google TTS voice"
        }
        selectedEngineValue.text = engine.label
        selectedVoiceValue.text = label
        selectedVoiceSpeedValue.text = formatVoiceSpeed(SherpaTtsPreferences.getVoiceSpeed(this))
        voiceSettingsButton.text = when (engine) {
            SpeechEnginePreferences.SpeechEngine.PIPER -> "Choose Piper voice"
            SpeechEnginePreferences.SpeechEngine.GOOGLE -> "Choose Google TTS voice"
        }
    }

    private fun formatVoiceSpeed(speed: Float): String {
        return "Speed ${String.format(java.util.Locale.US, "%.2fx", speed)}"
    }

    private fun updateServerAddressSummary() {
        serverAddressValue.text = VoiceBridgePreferences.getDisplayValue(this)
    }

    private fun restoreImportedAudioState() {
        selectedImportedAudioUri = ImportedAudioPreferences.getSelectedUri(this)?.let(Uri::parse)
        importedTailSecondsInput.setText(ImportedAudioPreferences.getTailSeconds(this).toString())
        updateImportedAudioSummary()
    }

    private fun updateImportedAudioSummary() {
        selectedAudioValue.text = ImportedAudioPreferences.getSelectedDisplayName(this)
    }

    private fun selectImportedAudioFile() {
        selectImportedAudioLauncher.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/*"))
    }

    private fun chooseLatestRecordingWithPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        ) {
            chooseLatestRecording()
        } else {
            requestAudioLibraryPermissionLauncher.launch(permission)
        }
    }

    private fun chooseLatestRecording() {
        coroutineScope.launch(Dispatchers.IO) {
            val result = runCatching { findLatestRecordingInRecordersFolder() }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val latest = result.getOrElse { error ->
                    Log.e(TAG, "Failed to find latest recording", error)
                    Toast.makeText(
                        this@MainActivity,
                        "Could not search the Recorders folder: ${error.message ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                if (latest == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "No MP3 or M4A recording found in the Recorders folder",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                selectedImportedAudioUri = latest.first
                ImportedAudioPreferences.saveSelectedAudio(
                    this@MainActivity,
                    latest.first.toString(),
                    latest.second
                )
                updateImportedAudioSummary()
                Toast.makeText(
                    this@MainActivity,
                    "Selected latest recording: ${latest.second}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun findLatestRecordingInRecordersFolder(): Pair<Uri, String>? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            MediaStore.Audio.Media.DATA
        }
        val selection = "$pathColumn LIKE ?"
        val selectionArgs = arrayOf("%Recorders/%")
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex).orEmpty()
                if (!displayName.endsWith(".mp3", ignoreCase = true) &&
                    !displayName.endsWith(".m4a", ignoreCase = true)
                ) {
                    continue
                }
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                return uri to displayName
            }
        }
        return null
    }

    private fun resolveImportedAudioDisplayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val columnIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (columnIndex < 0) {
                    null
                } else {
                    cursor.getString(columnIndex)
                }
            }
            ?: (uri.lastPathSegment ?: "Selected audio")
    }

    private fun sendImportedAudioTail() {
        val uri = selectedImportedAudioUri
        if (uri == null) {
            Toast.makeText(this, "Choose an MP3 or M4A recording first", Toast.LENGTH_LONG).show()
            return
        }
        val tailSeconds = importedTailSecondsInput.text.toString().trim().toIntOrNull()
        if (tailSeconds == null || tailSeconds <= 0) {
            Toast.makeText(this, "Enter how many seconds from the end to send", Toast.LENGTH_LONG).show()
            return
        }
        ImportedAudioPreferences.saveTailSeconds(this, tailSeconds)
        sendImportedAudioButton.isEnabled = false
        sendImportedAudioButton.text = "Sending..."
        importedResultValue.text = "Extracting selected recording tail..."

        coroutineScope.launch {
            try {
                val result = ImportedAudioTailSender.sendTail(
                    context = this@MainActivity,
                    uri = uri,
                    tailSeconds = tailSeconds,
                    whisperBaseUrl = VoiceBridgePreferences.getWhisperBaseUrl(this@MainActivity)
                )
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Imported audio transcription", result.transcription))
                importedResultValue.text =
                    "Transcription (copied to clipboard):\n${result.transcription}\n\n${result.timingSummary}"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Transcription copied")
                    .setMessage(result.transcription)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send imported audio tail", e)
                importedResultValue.text =
                    "Error: ${e.message ?: "Unknown error"}\n\n" +
                        DebugTimingStore.getLastTiming(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    "Imported audio failed: ${e.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                sendImportedAudioButton.isEnabled = true
                sendImportedAudioButton.text = "Transcribe selected tail"
            }
        }
    }

    private fun openServerSettingsDialog() {
        val hostInput = EditText(this).apply {
            hint = "Host or domain"
            setText(VoiceBridgePreferences.getHost(this@MainActivity))
            setSingleLine()
        }
        val portInput = EditText(this).apply {
            hint = "Port"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(VoiceBridgePreferences.getPort(this@MainActivity).toString())
            setSingleLine()
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(hostInput)
            addView(portInput)
        }

        AlertDialog.Builder(this)
            .setTitle("PC server address")
            .setMessage("Set the IP address or domain and port for the local voice bridge.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().trim().toIntOrNull()
                if (host.isBlank()) {
                    Toast.makeText(this, "Host or domain is required", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (port == null || port !in 1..65535) {
                    Toast.makeText(this, "Port must be between 1 and 65535", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                VoiceBridgePreferences.save(this, host, port)
                updateServerAddressSummary()
                Toast.makeText(this, "PC server address updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshDebugTimingPanel() {
        debugTimingValue.text = DebugTimingStore.getLastTiming(this)
    }

    private fun startDebugTimingRefreshLoop() {
        debugRefreshJob?.cancel()
        debugRefreshJob = coroutineScope.launch {
            while (isActive) {
                refreshDebugTimingPanel()
                delay(1000)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "$packageName/${TextPasterAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        
        val isEnabled = enabledServices.contains(packageName) && 
                       enabledServices.contains("TextPasterAccessibilityService")
        
        Log.d("MainActivity", "Accessibility check: $isEnabled (Found in: $enabledServices)")
        return isEnabled
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun handleLoginOrLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out") { _, _ ->
                earpieceaiApi.logout()
                launchLoginAndFinish()
                Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openProfile() {
        startActivity(Intent(this, ProfileActivity::class.java))
    }

    private fun refreshSession() {
        coroutineScope.launch {
            val result = earpieceaiApi.validate()
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is UnauthorizedException) {
                    earpieceaiApi.logout()
                    launchLoginAndFinish()
                } else {
                    Toast.makeText(this@MainActivity, "Profile refresh failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun ensureLoggedIn(): Boolean {
        if (!earpieceaiApi.isLoggedIn()) {
            launchLoginAndFinish()
            return false
        }
        return true
    }

    private fun launchLoginAndFinish() {
        val intent = Intent(this, ServerConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        sherpaSpeechController.release()
        googleTtsController.release()
        coroutineScope.cancel()
    }

    private fun preloadSelectedSpeechEngine() {
        when (SpeechEnginePreferences.getSelectedEngine(this)) {
            SpeechEnginePreferences.SpeechEngine.PIPER -> {
                coroutineScope.launch(Dispatchers.Default) {
                    runCatching {
                        sherpaTtsEngine.preload(
                            SherpaTtsPreferences.getSelectedVoice(this@MainActivity),
                            SherpaTtsPreferences.getVoiceSpeed(this@MainActivity)
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Sherpa preload failed in activity", error)
                    }
                }
            }
            SpeechEnginePreferences.SpeechEngine.GOOGLE -> {
                googleTtsController.warmUp { result ->
                    result.onSuccess {
                        googleTtsController.applySavedVoice { applyResult ->
                            applyResult.onSuccess { label ->
                                runOnUiThread { updateSelectedVoiceSummary(label) }
                            }.onFailure { error ->
                                Log.w(TAG, "Google TTS applySavedVoice failed in activity", error)
                            }
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Google TTS warmup failed in activity", error)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        debugRefreshJob?.cancel()
        debugRefreshJob = null
        usageRetryJob?.cancel()
        usageRetryJob = null
        if (earpieceaiApi.isLoggedIn()) {
            coroutineScope.launch {
                val result = earpieceaiApi.flushPendingUsage()
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    if (error is UnauthorizedException) {
                        earpieceaiApi.logout()
                        launchLoginAndFinish()
                    }
                }
            }
        }
    }

    private fun startUsageRetryLoop() {
        if (usageRetryJob != null) {
            return
        }
        usageRetryJob = coroutineScope.launch {
            while (isActive) {
                delay(60_000)
                val result = earpieceaiApi.flushPendingUsage()
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    if (error is UnauthorizedException) {
                        earpieceaiApi.logout()
                        launchLoginAndFinish()
                        return@launch
                    }
                }
            }
        }
    }
}
