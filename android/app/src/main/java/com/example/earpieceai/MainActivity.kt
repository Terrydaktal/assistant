package com.example.earpieceai

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "MainActivity"
        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        private const val REQUEST_CHECK_TTS_DATA = 201
    }

    private lateinit var startServiceButton: Button
    private lateinit var stopServiceButton: Button
    private lateinit var testSpeechButton: Button
    private lateinit var enableAccessibilityButton: Button
    private lateinit var configButton: Button
    private lateinit var profileButton: Button
    
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
    private var testTts: TextToSpeech? = null
    private var pendingTestSpeech: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        earpieceaiApi = EarpieceAiApi(this)

        setContentView(R.layout.activity_main)

        startServiceButton = findViewById(R.id.start_service_button)
        stopServiceButton = findViewById(R.id.stop_service_button)
        testSpeechButton = findViewById(R.id.test_speech_button)
        enableAccessibilityButton = findViewById(R.id.enable_accessibility_button)
        configButton = findViewById(R.id.config_button)
        profileButton = findViewById(R.id.profile_button)
        
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
        profileButton.setOnClickListener { openProfile() }
        configButton.setOnClickListener { handleLoginOrLogout() }
    }

    override fun onInit(status: Int) {
        Log.d(TAG, "TTS onInit status=$status")
        if (status != TextToSpeech.SUCCESS) {
            testTts?.shutdown()
            testTts = null
            pendingTestSpeech = null
            showTtsSetupDialog()
            return
        }

        val engine = testTts ?: return
        val languageResult = engine.setLanguage(Locale.getDefault())
        Log.d(TAG, "TTS setLanguage(${Locale.getDefault()}) result=$languageResult")
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallbackResult = engine.setLanguage(Locale.US)
            Log.d(TAG, "TTS setLanguage(${Locale.US}) fallback result=$fallbackResult")
            if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "TTS language data missing", Toast.LENGTH_LONG).show()
                showTtsSetupDialog()
                return
            }
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        pendingTestSpeech?.let {
            pendingTestSpeech = null
            speakTestText(it)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CHECK_TTS_DATA) {
            return
        }

        Log.d(TAG, "TTS data check result=$resultCode")
        if (resultCode == Engine.CHECK_VOICE_DATA_PASS) {
            initializeTestTts()
        } else {
            Toast.makeText(this, "TTS voice data missing: $resultCode", Toast.LENGTH_LONG).show()
            showTtsSetupDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
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

        logVisibleTtsEngines()
        if (testTts == null) {
            pendingTestSpeech = message
            checkTtsDataThenInitialize()
            return
        }
        speakTestText(message)
    }

    private fun checkTtsDataThenInitialize() {
        val checkIntent = Intent(Engine.ACTION_CHECK_TTS_DATA).setPackage(GOOGLE_TTS_ENGINE)
        if (checkIntent.resolveActivity(packageManager) == null) {
            Log.w(TAG, "No TTS data checker found; initializing explicit engine")
            initializeTestTts()
            return
        }
        startActivityForResult(checkIntent, REQUEST_CHECK_TTS_DATA)
    }

    private fun initializeTestTts() {
        testTts?.shutdown()
        testTts = TextToSpeech(this, this, GOOGLE_TTS_ENGINE)
    }

    private fun logVisibleTtsEngines() {
        val services = packageManager.queryIntentServices(
            Intent(Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val engines = services.joinToString(", ") { service ->
            service.serviceInfo?.packageName.orEmpty()
        }.ifBlank { "none" }
        Log.d(TAG, "Visible TTS engines: $engines")
        Toast.makeText(this, "Visible TTS engines: $engines", Toast.LENGTH_LONG).show()
    }

    private fun showTtsSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("TTS init failed")
            .setMessage("Android did not provide a usable Text-to-Speech engine. Install or enable a TTS engine, then set it as the preferred engine.")
            .setPositiveButton("TTS Settings") { _, _ -> openTtsSettings() }
            .setNegativeButton("Install Voice Data") { _, _ -> openTtsDataInstaller() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun openTtsSettings() {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        startFirstAvailableIntent(intents)
    }

    private fun openTtsDataInstaller() {
        val intents = listOf(
            Intent(Engine.ACTION_INSTALL_TTS_DATA),
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        startFirstAvailableIntent(intents)
    }

    private fun startFirstAvailableIntent(intents: List<Intent>) {
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null }
        if (intent == null) {
            Toast.makeText(this, "No matching settings screen found", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent)
    }

    private fun speakTestText(message: String) {
        val result = testTts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "main_test_speech")
        Toast.makeText(this, "TTS speak() result: $result", Toast.LENGTH_SHORT).show()
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
        testTts?.stop()
        testTts?.shutdown()
        coroutineScope.cancel()
    }

    override fun onStop() {
        super.onStop()
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
