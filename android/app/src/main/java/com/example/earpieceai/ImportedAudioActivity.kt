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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
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

    private data class RecordingCandidate(
        val uri: Uri,
        val displayName: String,
        val modifiedMs: Long,
        val sizeBytes: Long
    )

    private data class DocumentFolder(
        val documentId: String,
        val depth: Int
    )

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

    private val selectRecordersFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not persist read permission for Recorders folder", error)
            }
            ImportedAudioPreferences.saveRecordersTreeUri(this, uri.toString())
            chooseLatestRecording()
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
        selectAudioLauncher.launch(arrayOf("audio/*"))
    }

    private fun chooseLatestRecordingWithPermission() {
        if (ImportedAudioPreferences.getRecordersTreeUri(this) == null) {
            Toast.makeText(
                this,
                "Select the Recorders folder once so Assistant can see the file while it is recording",
                Toast.LENGTH_LONG
            ).show()
            selectRecordersFolderLauncher.launch(null)
            return
        }
        chooseLatestRecording()
    }

    private fun chooseLatestRecording() {
        activityScope.launch(Dispatchers.IO) {
            val treeUri = ImportedAudioPreferences.getRecordersTreeUri(this@ImportedAudioActivity)
                ?.let(Uri::parse)
            val treeResult = runCatching {
                treeUri?.let(::findLatestRecordingInDocumentTree)
            }
            val mediaStoreResult = if (hasAudioLibraryPermission()) {
                runCatching { findLatestRecordingInMediaStore() }.getOrNull()
            } else {
                null
            }
            withContext(Dispatchers.Main) {
                val treeError = treeResult.exceptionOrNull()
                if (treeError != null) {
                    Log.e(TAG, "Failed to read the selected Recorders folder", treeError)
                    ImportedAudioPreferences.clearRecordersTreeUri(this@ImportedAudioActivity)
                    Toast.makeText(
                        this@ImportedAudioActivity,
                        "Recorders folder access expired. Tap again and select the folder.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                val latest = listOfNotNull(treeResult.getOrNull(), mediaStoreResult)
                    .maxWithOrNull(compareBy<RecordingCandidate> { it.modifiedMs }.thenBy { it.sizeBytes })
                if (latest == null) {
                    Toast.makeText(
                        this@ImportedAudioActivity,
                        "No supported audio recording found in the selected Recorders folder",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                selectedAudioUri = latest.uri
                ImportedAudioPreferences.saveSelectedAudio(
                    this@ImportedAudioActivity,
                    latest.uri.toString(),
                    latest.displayName
                )
                updateSelectedAudioSummary()
                Toast.makeText(
                    this@ImportedAudioActivity,
                    "Selected latest recording: ${latest.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun hasAudioLibraryPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun findLatestRecordingInDocumentTree(treeUri: Uri): RecordingCandidate? {
        val folders = ArrayDeque<DocumentFolder>()
        folders.add(DocumentFolder(DocumentsContract.getTreeDocumentId(treeUri), 0))
        var latest: RecordingCandidate? = null

        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder.documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            )
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idIndex)
                    val displayName = cursor.getString(nameIndex).orEmpty()
                    val mimeType = cursor.getString(mimeIndex).orEmpty()
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (folder.depth < 3) {
                            folders.add(DocumentFolder(documentId, folder.depth + 1))
                        }
                        continue
                    }
                    if (!isSupportedRecording(displayName, mimeType)) continue

                    val candidate = RecordingCandidate(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        displayName = displayName,
                        modifiedMs = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                        sizeBytes = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                    )
                    if (latest == null || candidate.modifiedMs > latest.modifiedMs ||
                        candidate.modifiedMs == latest.modifiedMs && candidate.sizeBytes > latest.sizeBytes
                    ) {
                        latest = candidate
                    }
                }
            }
        }
        return latest
    }

    private fun findLatestRecordingInMediaStore(): RecordingCandidate? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
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
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex).orEmpty()
                if (!isSupportedRecording(displayName, "audio/*")) continue
                return RecordingCandidate(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex)),
                    displayName = displayName,
                    modifiedMs = cursor.getLong(modifiedIndex) * 1_000L,
                    sizeBytes = cursor.getLong(sizeIndex)
                )
            }
        }
        return null
    }

    private fun isSupportedRecording(displayName: String, mimeType: String): Boolean {
        val lowerName = displayName.lowercase(Locale.US)
        return mimeType.startsWith("audio/", ignoreCase = true) ||
            lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a") ||
            lowerName.endsWith(".opus") || lowerName.endsWith(".ogg") ||
            lowerName.endsWith(".webm") || lowerName.endsWith(".wav") ||
            lowerName.contains(".mp3.") || lowerName.contains(".m4a.") ||
            lowerName.contains(".opus.") || lowerName.contains(".ogg.") ||
            lowerName.contains(".webm.") || lowerName.contains(".wav.")
    }

    private fun resolveAudioDisplayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex < 0) null else cursor.getString(columnIndex)
            }
            ?: (uri.lastPathSegment ?: "Selected audio")
    }

    private fun sendAudioTail() {
        val uri = selectedAudioUri
        if (uri == null) {
            Toast.makeText(this, "Choose an audio recording first", Toast.LENGTH_LONG).show()
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
