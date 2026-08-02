package com.example.earpieceai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ImportedAudioActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ImportedAudioActivity"
    }

    private lateinit var selectedAudioValue: TextView
    private lateinit var tailSecondsInput: EditText
    private lateinit var sendButton: Button
    private lateinit var progressValue: TextView
    private lateinit var resultValue: TextView
    private lateinit var serverValue: TextView

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val progressLines = mutableListOf<String>()
    private var selectedAudioUri: Uri? = null
    private var sendStartedAt = 0L

    private val selectAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not persist read permission for imported audio URI", error)
            }
            selectedAudioUri = uri
            val displayName = resolveAudioDisplayName(uri)
            ImportedAudioPreferences.saveSelectedAudio(this, uri.toString(), displayName)
            updateSelectedAudioSummary()
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
        setContentView(R.layout.activity_imported_audio)

        selectedAudioValue = findViewById(R.id.selected_audio_value)
        tailSecondsInput = findViewById(R.id.imported_tail_seconds_input)
        sendButton = findViewById(R.id.send_imported_audio_button)
        progressValue = findViewById(R.id.imported_progress_value)
        resultValue = findViewById(R.id.imported_result_value)
        serverValue = findViewById(R.id.imported_server_value)

        findViewById<Button>(R.id.imported_audio_back_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.select_audio_button).setOnClickListener { selectAudioFile() }
        findViewById<Button>(R.id.select_latest_recording_button).setOnClickListener {
            chooseLatestRecordingWithPermission()
        }
        sendButton.setOnClickListener { sendAudioTail() }

        selectedAudioUri = ImportedAudioPreferences.getSelectedUri(this)?.let(Uri::parse)
        tailSecondsInput.setText(ImportedAudioPreferences.getTailSeconds(this).toString())
        updateSelectedAudioSummary()
        updateServerSummary()
    }

    override fun onResume() {
        super.onResume()
        updateServerSummary()
    }

    private fun updateServerSummary() {
        serverValue.text = "Whisper server: ${VoiceBridgePreferences.getWhisperBaseUrl(this)}"
    }

    private fun updateSelectedAudioSummary() {
        selectedAudioValue.text = ImportedAudioPreferences.getSelectedDisplayName(this)
    }

    private fun selectAudioFile() {
        selectAudioLauncher.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/*"))
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
        activityScope.launch(Dispatchers.IO) {
            val result = runCatching { findLatestRecordingInRecordersFolder() }
            withContext(Dispatchers.Main) {
                val latest = result.getOrElse { error ->
                    Log.e(TAG, "Failed to find latest recording", error)
                    Toast.makeText(
                        this@ImportedAudioActivity,
                        "Could not search the Recorders folder: ${error.message ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                if (latest == null) {
                    Toast.makeText(
                        this@ImportedAudioActivity,
                        "No MP3 or M4A recording found in the Recorders folder",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                selectedAudioUri = latest.first
                ImportedAudioPreferences.saveSelectedAudio(
                    this@ImportedAudioActivity,
                    latest.first.toString(),
                    latest.second
                )
                updateSelectedAudioSummary()
                Toast.makeText(
                    this@ImportedAudioActivity,
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
                return ContentUris.withAppendedId(collection, cursor.getLong(idIndex)) to displayName
            }
        }
        return null
    }

    private fun resolveAudioDisplayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (columnIndex < 0) null else cursor.getString(columnIndex)
            }
            ?: (uri.lastPathSegment ?: "Selected audio")
    }

    private fun sendAudioTail() {
        val uri = selectedAudioUri
        if (uri == null) {
            Toast.makeText(this, "Choose an MP3 or M4A recording first", Toast.LENGTH_LONG).show()
            return
        }
        val tailSeconds = tailSecondsInput.text.toString().trim().toIntOrNull()
        if (tailSeconds == null || tailSeconds <= 0) {
            Toast.makeText(this, "Enter how many seconds from the end to send", Toast.LENGTH_LONG).show()
            return
        }

        ImportedAudioPreferences.saveTailSeconds(this, tailSeconds)
        sendButton.isEnabled = false
        sendButton.text = "Transcribing..."
        resultValue.text = "Waiting for transcription."
        progressLines.clear()
        progressValue.text = "Starting request..."
        sendStartedAt = SystemClock.elapsedRealtime()

        activityScope.launch {
            try {
                val result = ImportedAudioTailSender.sendTail(
                    context = this@ImportedAudioActivity,
                    uri = uri,
                    tailSeconds = tailSeconds,
                    whisperBaseUrl = VoiceBridgePreferences.getWhisperBaseUrl(this@ImportedAudioActivity)
                ) { update ->
                    runOnUiThread { appendProgress(update) }
                }

                val clipboardStartedAt = SystemClock.elapsedRealtime()
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Imported audio transcription", result.transcription)
                )
                val clipboardMs = SystemClock.elapsedRealtime() - clipboardStartedAt
                val totalMs = SystemClock.elapsedRealtime() - sendStartedAt
                appendProgress(
                    ImportedAudioTailSender.ProgressUpdate(
                        stage = "Timing complete; transcription copied to phone clipboard",
                        elapsedMs = totalMs,
                        stageMs = clipboardMs,
                        detail = result.timingSummary +
                            " client_clipboard_ms=$clipboardMs client_send_to_clipboard_ms=$totalMs"
                    )
                )
                resultValue.text = buildString {
                    append("Copied to clipboard in ${formatDuration(totalMs)}\n\n")
                    append(result.transcription)
                }
                Toast.makeText(this@ImportedAudioActivity, "Transcription copied", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to send imported audio tail", error)
                val totalMs = SystemClock.elapsedRealtime() - sendStartedAt
                appendProgress(
                    ImportedAudioTailSender.ProgressUpdate(
                        stage = "Request stopped with an error",
                        elapsedMs = totalMs,
                        detail = error.message ?: error.javaClass.simpleName
                    )
                )
                appendProgress(
                    ImportedAudioTailSender.ProgressUpdate(
                        stage = "Failure timing details",
                        elapsedMs = totalMs,
                        detail = DebugTimingStore.getLastTiming(this@ImportedAudioActivity)
                    )
                )
                resultValue.text =
                    "Error after ${formatDuration(totalMs)}: ${error.message ?: "Unknown error"}"
                Toast.makeText(
                    this@ImportedAudioActivity,
                    "Imported audio failed: ${error.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                sendButton.isEnabled = true
                sendButton.text = "Transcribe selected tail"
            }
        }
    }

    private fun appendProgress(update: ImportedAudioTailSender.ProgressUpdate) {
        val timing = buildString {
            if (update.stageMs >= 0) append("stage ${formatDuration(update.stageMs)} · ")
            append("elapsed ${formatDuration(update.elapsedMs)}")
        }
        val detail = update.detail.takeIf { it.isNotBlank() }?.let { "\n   $it" }.orEmpty()
        progressLines += "${progressLines.size + 1}. ${update.stage}\n   $timing$detail"
        progressValue.text = progressLines.joinToString("\n\n")
    }

    private fun formatDuration(milliseconds: Long): String {
        if (milliseconds < 0) return "not available"
        if (milliseconds < 1_000) return "$milliseconds ms"
        val minutes = milliseconds / 60_000
        val seconds = (milliseconds % 60_000) / 1_000.0
        return if (minutes > 0) {
            String.format(Locale.US, "%dm %.1fs", minutes, seconds)
        } else {
            String.format(Locale.US, "%.2fs", seconds)
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
