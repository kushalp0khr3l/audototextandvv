package com.example.localaudiototext

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Manages Text-To-Speech using the native Android TextToSpeech API.
 *
 * Usage:
 *  val ttsManager = TextToSpeechManager(context)
 *  ttsManager.initialize()          // must be called first; fires onReady when done
 *  ttsManager.speak("Hello world")
 *  ttsManager.stop()
 *  ttsManager.release()             // call in onDestroy()
 */
class TextToSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val UTTERANCE_ID = "tts_utterance"
    }

    enum class State {
        UNINITIALIZED,
        IDLE,
        LOADING,
        SPEAKING
    }

    var state = State.UNINITIALIZED
        private set

    /** Callback invoked whenever state changes — use this to update UI. */
    var onStateChanged: ((State) -> Unit)? = null

    /** Callback fired when speech finishes naturally (not cancelled). */
    var onSpeakComplete: (() -> Unit)? = null

    /** Callback fired on any TTS error. */
    var onError: ((String) -> Unit)? = null

    private var tts: TextToSpeech? = null

    /**
     * Initialize the TTS engine. This is asynchronous; the engine will be
     * ready shortly after the callback returns SUCCESS.
     *
     * Safe to call multiple times — ignored if already initialized.
     */
    fun initialize() {
        if (state != State.UNINITIALIZED) return
        updateState(State.LOADING)

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "US English is not supported on this device")
                    updateState(State.UNINITIALIZED)
                    onError?.invoke("TTS language not supported. Please install English voice data.")
                } else {
                    setupUtteranceListener()
                    updateState(State.IDLE)
                    Log.i(TAG, "TTS engine initialized successfully")
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                updateState(State.UNINITIALIZED)
                onError?.invoke("TTS engine failed to initialize.")
            }
        }
    }

    /**
     * Speak the given text aloud.
     * If already speaking, the previous speech is stopped first.
     */
    fun speak(text: String) {
        val engine = tts
        if (engine == null || state == State.UNINITIALIZED || state == State.LOADING) {
            Log.w(TAG, "speak() called but TTS not ready (state=$state)")
            onError?.invoke("TTS engine is not ready yet.")
            return
        }
        if (text.isBlank()) {
            Log.w(TAG, "speak() called with blank text — ignoring")
            return
        }

        if (state == State.SPEAKING) {
            engine.stop()
        }

        updateState(State.SPEAKING)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Stop any ongoing speech.
     */
    fun stop() {
        tts?.stop()
        if (state == State.SPEAKING) {
            updateState(State.IDLE)
        }
    }

    /**
     * Compatibility shim — called by MainActivity before STT recording starts.
     * With native TTS, we simply stop any current playback; no model to unload.
     */
    fun unloadModel() {
        stop()
    }

    /**
     * Release all resources permanently. Call from Activity.onDestroy().
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        state = State.UNINITIALIZED
    }

    // ── Internal ──

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (state == State.SPEAKING) {
                    updateState(State.IDLE)
                    onSpeakComplete?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Utterance error for id: $utteranceId")
                updateState(State.IDLE)
                onError?.invoke("TTS playback error.")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "Utterance error for id: $utteranceId, code: $errorCode")
                updateState(State.IDLE)
                onError?.invoke("TTS playback error (code $errorCode).")
            }
        })
    }

    private fun updateState(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}
