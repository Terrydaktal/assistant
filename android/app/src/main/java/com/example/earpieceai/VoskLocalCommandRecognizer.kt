package com.example.earpieceai

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskLocalCommandRecognizer(
    private val context: Context,
    private val onCommandText: (String) -> Unit,
    private val onStreamCommandText: (String, Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onUnavailable: (String) -> Unit
) : RecognitionListener {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var streamRecognizer: Recognizer? = null
    private var isListening = false
    private var isReady = false
    private var streamProcessedSamples = 0
    private var lastStreamEmission: Pair<String, Int>? = null

    fun initialize() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        onStatus("Preparing offline command recognizer...")
        StorageService.unpack(
            context,
            "model-en-us",
            "model",
            { loadedModel ->
                model = loadedModel
                isReady = true
                onStatus("Offline command recognizer ready.")
                startListening()
            },
            { exception ->
                val message = exception.message ?: exception.javaClass.simpleName
                Log.e(TAG, "Failed to initialize Vosk model", exception)
                onUnavailable("Offline command recognizer unavailable: $message")
            }
        )
    }

    fun startListening() {
        if (!isReady || isListening || speechService != null) {
            return
        }
        val loadedModel = model ?: return
        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE, LISTENING_COMMAND_GRAMMAR)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            isListening = true
            onStatus("Offline commands active: say kilo vesta begin.")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start Vosk recognizer", e)
            onUnavailable("Offline command recognizer failed: ${e.message ?: "unknown error"}")
        }
    }

    fun stopListening() {
        isListening = false
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    fun beginStreamMode(): Boolean {
        val loadedModel = model ?: return false
        stopListening()
        closeStreamRecognizer()
        return try {
            streamRecognizer = Recognizer(loadedModel, SAMPLE_RATE, STREAM_COMMAND_GRAMMAR)
            streamProcessedSamples = 0
            lastStreamEmission = null
            onStatus("AI recording active. Say kilo vesta stop to send or kilo vesta cancel to discard.")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start Vosk stream recognizer", e)
            false
        }
    }

    fun acceptStreamAudio(buffer: ShortArray, count: Int) {
        val recognizer = streamRecognizer ?: return
        streamProcessedSamples += count
        val hasResult = recognizer.acceptWaveForm(buffer, count)
        val hypothesis = if (hasResult) recognizer.result else recognizer.partialResult
        emitStreamRecognizedText(hypothesis)
    }

    fun endStreamMode() {
        closeStreamRecognizer()
        streamProcessedSamples = 0
        lastStreamEmission = null
        startListening()
    }

    fun shutdown() {
        stopListening()
        closeStreamRecognizer()
        model?.close()
        model = null
        isReady = false
    }

    override fun onPartialResult(hypothesis: String?) {
        emitRecognizedText(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        emitRecognizedText(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        emitRecognizedText(hypothesis)
        isListening = false
        speechService?.shutdown()
        speechService = null
        startListening()
    }

    override fun onError(exception: Exception?) {
        isListening = false
        speechService?.shutdown()
        speechService = null
        val message = exception?.message ?: "unknown error"
        Log.e(TAG, "Vosk local recognizer error", exception)
        onStatus("Offline command recognizer restarting: $message")
        startListening()
    }

    override fun onTimeout() {
        isListening = false
        speechService?.shutdown()
        speechService = null
        startListening()
    }

    private fun emitRecognizedText(hypothesis: String?) {
        val text = extractRecognizedText(hypothesis)
        if (text.isNotBlank() && text != "[unk]") {
            onCommandText(text)
        }
    }

    private fun emitStreamRecognizedText(hypothesis: String?) {
        val text = extractRecognizedText(hypothesis)
        if (text.isBlank() || text == "[unk]") {
            return
        }
        val emission = text to streamProcessedSamples
        if (lastStreamEmission == emission) {
            return
        }
        lastStreamEmission = emission
        onStreamCommandText(text, streamProcessedSamples)
    }

    private fun extractRecognizedText(hypothesis: String?): String {
        return readText(hypothesis, "partial").ifBlank { readText(hypothesis, "text") }
    }

    private fun readText(hypothesis: String?, key: String): String {
        if (hypothesis.isNullOrBlank()) {
            return ""
        }
        return try {
            JSONObject(hypothesis).optString(key, "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun closeStreamRecognizer() {
        streamRecognizer?.close()
        streamRecognizer = null
        streamProcessedSamples = 0
        lastStreamEmission = null
    }

    companion object {
        private const val TAG = "VoskLocalCommand"
        private const val SAMPLE_RATE = 16_000.0f
        private val WAKE_PHRASES = listOf(
            "kilo vesta",
            "kilo festa",
            "kilo fester",
            "kilo vestor",
            "keelo vesta",
            "keelo festa"
        )
        private val LISTENING_COMMAND_GRAMMAR = buildGrammar(
            listOf(
                "begin",
                "start",
                "create new chat",
                "new chat",
                "list chats",
                "list current chats",
                "show chats",
                "select chat one",
                "select chat two",
                "select chat three",
                "select chat four",
                "select chat five",
                "select chat six",
                "select chat seven",
                "select chat eight",
                "select chat nine",
                "select chat ten",
                "select chat 1",
                "select chat 2",
                "select chat 3",
                "select chat 4",
                "select chat 5",
                "select chat 6",
                "select chat 7",
                "select chat 8",
                "select chat 9",
                "select chat 10",
                "select chat number one",
                "select chat number two",
                "select chat number three",
                "select chat number four",
                "select chat number five",
                "select chat number six",
                "select chat number seven",
                "select chat number eight",
                "select chat number nine",
                "select chat number ten",
                "go back",
                "back",
                "go forward",
                "forward",
                "cancel",
                "discard",
                "abort",
                "end",
                "send",
                "finish",
                "done",
                "stop",
                "stop talking",
                "stop speaking",
                "rewind",
                "repeat",
                "repeat that",
                "repeat last message"
            )
        )
        private val STREAM_COMMAND_GRAMMAR = buildGrammar(
            listOf(
                "stop",
                "stop talking",
                "stop speaking",
                "cancel",
                "discard",
                "abort"
            )
        )

        private fun buildGrammar(suffixes: List<String>): String {
            val commands = WAKE_PHRASES.flatMap { wake ->
                suffixes.map { suffix -> "$wake $suffix" }
            } + "[unk]"
            return commands.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        }
    }
}
