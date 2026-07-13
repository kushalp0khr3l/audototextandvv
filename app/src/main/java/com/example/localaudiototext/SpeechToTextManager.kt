package com.example.localaudiototext

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Manages Speech-To-Text using the native Android SpeechRecognizer API.
 *
 * Handles the "hold-to-talk" pattern correctly by auto-restarting the recognizer
 * when it stops due to silence (which it does frequently), accumulating text
 * across multiple recognition cycles for as long as the button is held.
 *
 * Uses EXTRA_PREFER_OFFLINE = true to request offline recognition.
 */
class SpeechToTextManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToTextManager"
    }

    enum class State {
        UNINITIALIZED,
        IDLE,
        RECORDING,
        TRANSCRIBING
    }

    var state = State.IDLE
        private set

    private var recognizer: SpeechRecognizer? = null
    private var finalResultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null

    // Accumulates confirmed sentences across auto-restart cycles.
    private val accumulatedText = StringBuilder()
    // Latest partial text from the current cycle (not yet confirmed).
    private var lastPartialText = ""
    // True while the user is holding the button down.
    @Volatile private var isUserListening = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            Log.e(TAG, "SpeechRecognizer not available on this device")
        }
    }

    /**
     * Start listening. Calls [onPartialResult] with live partial results.
     *
     * @return true if listening started successfully
     */
    fun startListening(onPartialResult: ((String) -> Unit)? = null): Boolean {
        if (recognizer == null) {
            Log.e(TAG, "SpeechRecognizer is not available")
            return false
        }
        if (state == State.RECORDING || state == State.TRANSCRIBING) return false

        // Reset state for this new session
        accumulatedText.clear()
        lastPartialText = ""
        finalResultCallback = null
        partialResultCallback = onPartialResult
        isUserListening = true

        startRecognizerInternal()
        state = State.RECORDING
        return true
    }

    /**
     * Stop the microphone and collect the final accumulated result.
     *
     * @param onResult Callback with the full transcription text
     */
    fun stopListening(onResult: (String) -> Unit) {
        if (state != State.RECORDING && state != State.TRANSCRIBING) {
            onResult("")
            return
        }
        isUserListening = false
        finalResultCallback = onResult
        state = State.TRANSCRIBING
        recognizer?.stopListening()
    }

    /**
     * Release all resources. Call from Activity.onDestroy().
     */
    fun release() {
        isUserListening = false
        recognizer?.destroy()
        recognizer = null
        state = State.UNINITIALIZED
    }

    // ── Internal ──

    /**
     * (Re-)starts the underlying SpeechRecognizer. Called on first start and
     * automatically after each auto-stop cycle while the user is still holding.
     */
    private fun startRecognizerInternal() {
        val rec = recognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech detected")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                if (partial.isNotBlank()) {
                    lastPartialText = partial
                    // Show accumulated + current partial to user
                    partialResultCallback?.invoke(buildCurrentText())
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""

                Log.d(TAG, "onResults: '$text', userListening=$isUserListening")

                // Commit this cycle's result into the accumulator
                val committed = if (text.isNotBlank()) text else lastPartialText
                if (committed.isNotBlank()) {
                    if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                    accumulatedText.append(committed)
                }
                lastPartialText = ""

                if (isUserListening) {
                    // User is still holding — show committed text and restart silently
                    partialResultCallback?.invoke(accumulatedText.toString())
                    startRecognizerInternal()
                } else {
                    // User has released — deliver final result
                    deliverResult()
                }
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Recognition error: ${recognizerErrorToString(error)}, userListening=$isUserListening")

                val isRecoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

                if (isUserListening && isRecoverable) {
                    // Auto-stopped with a soft error while holding — restart silently
                    startRecognizerInternal()
                } else {
                    // Unrecoverable OR user already released — deliver what we have
                    deliverResult()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        rec.startListening(intent)
    }

    /** Combines accumulated confirmed sentences with any trailing partial. */
    private fun buildCurrentText(): String {
        val acc = accumulatedText.toString()
        return when {
            acc.isNotBlank() && lastPartialText.isNotBlank() -> "$acc $lastPartialText"
            acc.isNotBlank() -> acc
            else -> lastPartialText
        }
    }

    /** Fires the final callback with everything captured so far. */
    private fun deliverResult() {
        val finalText = buildCurrentText()
        Log.d(TAG, "Delivering final result: '$finalText'")
        state = State.IDLE
        finalResultCallback?.invoke(finalText)
        finalResultCallback = null
        accumulatedText.clear()
        lastPartialText = ""
    }

    private fun recognizerErrorToString(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        else -> "Unknown error ($error)"
    }
}
