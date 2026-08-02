package com.example.swiftsay

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var startServiceButton: Button
    private lateinit var stopServiceButton: Button
    private lateinit var enableAccessibilityButton: Button
    private lateinit var configButton: Button
    private lateinit var btnStep1: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnMic: Button
    private lateinit var step1Title: TextView
    private lateinit var step2Title: TextView
    private lateinit var step3Title: TextView
    private lateinit var step4Title: TextView
    private lateinit var serverValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startServiceButton = findViewById(R.id.start_service_button)
        stopServiceButton = findViewById(R.id.stop_service_button)
        enableAccessibilityButton = findViewById(R.id.enable_accessibility_button)
        configButton = findViewById(R.id.config_button)
        btnStep1 = findViewById(R.id.btn_step1)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnMic = findViewById(R.id.btn_mic)
        step1Title = findViewById(R.id.step1_title)
        step2Title = findViewById(R.id.step2_title)
        step3Title = findViewById(R.id.step3_title)
        step4Title = findViewById(R.id.step4_title)
        serverValue = findViewById(R.id.server_value)

        btnStep1.setOnClickListener { openAppInfo() }
        enableAccessibilityButton.setOnClickListener { openAccessibilitySettings() }
        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnMic.setOnClickListener { requestAudioPermission() }
        startServiceButton.setOnClickListener { checkAndStartFloatingService() }
        stopServiceButton.setOnClickListener { stopService(Intent(this, FloatingButtonService::class.java)) }
        configButton.setOnClickListener { startActivity(Intent(this, ServerConfigActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun updateUiState() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val audioGranted = checkAudioPermission()
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        serverValue.text = "Whisper server: ${LocalServerPreferences.getDisplayValue(this)}"
        step1Title.text = "Step 1: Allow Restricted Settings " + if (Build.VERSION.SDK_INT >= 33) "(info)" else "(ok)"

        step2Title.text = if (accessibilityEnabled) "Step 2: Enable SwiftSay Local Service (ok)"
        else "Step 2: Enable SwiftSay Local Service (required)"
        enableAccessibilityButton.isEnabled = !accessibilityEnabled
        enableAccessibilityButton.text = if (accessibilityEnabled) "Enabled" else "Enable Accessibility"

        step3Title.text = if (overlayGranted) "Step 3: Display Over Other Apps (ok)"
        else "Step 3: Display Over Other Apps (required)"
        btnOverlay.isEnabled = !overlayGranted
        btnOverlay.text = if (overlayGranted) "Granted" else "Grant Overlay Permission"

        step4Title.text = if (audioGranted) "Step 4: Microphone Access (ok)"
        else "Step 4: Microphone Access (required)"
        btnMic.isEnabled = !audioGranted
        btnMic.text = if (audioGranted) "Granted" else "Grant Mic Permission"

        startServiceButton.isEnabled = overlayGranted && audioGranted
        startServiceButton.alpha = if (startServiceButton.isEnabled) 1.0f else 0.55f
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
        Toast.makeText(this, "Open the menu and allow restricted settings if required", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        if (!checkAudioPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateUiState()
    }

    private fun checkAndStartFloatingService() {
        if (!Settings.canDrawOverlays(this) || !checkAudioPermission()) {
            Toast.makeText(this, "Complete the overlay and microphone steps first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Not Enabled")
                .setMessage("Automatic text insertion will not work until the accessibility service is enabled. Start anyway?")
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val enabled = enabledServices.contains(packageName) &&
            enabledServices.contains(TextPasterAccessibilityService::class.java.simpleName)
        Log.d(TAG, "Accessibility enabled=$enabled services=$enabledServices")
        return enabled
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    companion object {
        private const val TAG = "SwiftSayLocalMain"
        private const val REQUEST_AUDIO = 102
    }
}
