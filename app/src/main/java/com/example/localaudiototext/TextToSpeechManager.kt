package com.example.localaudiototext

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Frontend-facing facade for Text-To-Speech using KittenTTS.
 *
 * Mirrors the simplicity of SpeechToTextManager. Handles:
 *  - Model loading and initialization
 *  - JSON input parsing
 *  - Audio playback via AudioTrack
 *  - Cancellation (for STT preemption)
 *
 * ## Usage (Frontend)
 * ```kotlin
 * val ttsManager = TextToSpeechManager(context)
 *
 * // Initialize once (e.g. in onCreate)
 * lifecycleScope.launch {
 *     ttsManager.initialize()
 * }
 *
 * // Speak from JSON
 * ttsManager.speak("""{"text":"Hello world","voice":"Hugo","speed":1.0}""")
 *
 * // Cancel (e.g. when STT is requested)
 * ttsManager.stop()
 *
 * // Cleanup (e.g. in onDestroy)
 * ttsManager.release()
 * ```
 *
 * ## JSON Input Format
 * ```json
 * {
 *   "text": "The text to speak",      // required
 *   "voice": "Hugo",                  // optional, default "Hugo"
 *   "speed": 1.0                       // optional, default 1.0
 * }
 * ```
 *
 * ## Available Voices
 * Bella, Jasper, Luna, Bruno, Rosie, Hugo, Kiki, Leo
 */
class TextToSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "TextToSpeechManager"
    }

    /**
     * Current state of the TTS manager.
     * - UNINITIALIZED: loadModel() has not been called yet
     * - IDLE: Ready to accept speak() calls
     * - LOADING: Model is being loaded (during initialize())
     * - SPEAKING: Currently synthesizing and playing audio
     */
    enum class State {
        UNINITIALIZED,
        IDLE,
        LOADING,
        SPEAKING
    }

    private val engine = KittenTTSEngine()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speakJob: Job? = null
    private var audioTrack: AudioTrack? = null

    /** Current state — observable by the frontend for UI updates. */
    var state = State.UNINITIALIZED
        private set

    /** Callback invoked when state changes. Set by frontend for UI updates. */
    var onStateChanged: ((State) -> Unit)? = null

    /** Callback invoked when speech playback completes naturally (not cancelled). */
    var onSpeakComplete: (() -> Unit)? = null

    /** Callback invoked when an error occurs during synthesis or playback. */
    var onError: ((String) -> Unit)? = null

    /**
     * Initialize the TTS engine. Loads the ONNX model, voice embeddings,
     * and espeak-ng phonemizer. Call once during app startup.
     *
     * This is a suspend function — call from a coroutine (e.g. lifecycleScope.launch).
     *
     * @throws RuntimeException if model files are missing from assets
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (state != State.UNINITIALIZED) return@withContext

        updateState(State.LOADING)
        try {
            engine.loadModel(context)
            updateState(State.IDLE)
            Log.i(TAG, "TTS engine initialized successfully")
        } catch (e: Exception) {
            updateState(State.UNINITIALIZED)
            Log.e(TAG, "TTS engine initialization failed", e)
            throw e
        }
    }

    /**
     * Speak text from a JSON-formatted string.
     *
     * JSON format:
     * ```json
     * {"text": "Hello world", "voice": "Hugo", "speed": 1.0}
     * ```
     *
     * - "text" is required
     * - "voice" defaults to "Hugo" if omitted
     * - "speed" defaults to 1.0 if omitted
     *
     * If currently speaking, the previous speech is cancelled first.
     * If state is not IDLE, the call is ignored (logs a warning).
     *
     * @param json JSON string with text, voice, and speed fields
     */
    fun speak(json: String) {
        if (state == State.UNINITIALIZED || state == State.LOADING) {
            Log.w(TAG, "speak() called but engine is $state — ignoring")
            onError?.invoke("TTS engine not ready (state: $state)")
            return
        }

        // Cancel any existing speech
        if (state == State.SPEAKING) {
            stopInternal()
        }

        // Parse JSON input
        val text: String
        val voice: String
        val speed: Float
        try {
            val obj = JSONObject(json)
            text = obj.getString("text")
            voice = obj.optString("voice", "Hugo")
            speed = obj.optDouble("speed", 1.0).toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON input: $json", e)
            onError?.invoke("Invalid JSON input: ${e.message}")
            return
        }

        if (text.isBlank()) {
            Log.w(TAG, "speak() called with blank text — ignoring")
            return
        }

        updateState(State.SPEAKING)

        speakJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Create AudioTrack for playback
                    val track = createAudioTrack()
                    audioTrack = track
                    track.play()

                    // Synthesize and play chunk by chunk
                    engine.synthesizeStreaming(text, voice, speed) { pcmChunk ->
                        if (!isActive) {
                            // Coroutine was cancelled (stop() was called)
                            return@synthesizeStreaming false
                        }
                        track.write(pcmChunk, 0, pcmChunk.size)
                        isActive // return whether to continue
                    }

                    // Wait for AudioTrack to finish playing remaining buffered audio
                    if (isActive) {
                        track.stop()
                        delay(300) // Ensure the hardware buffer fully flushes out the speaker
                    }
                    track.release()
                    audioTrack = null
                }

                // Only fire completion callback if not cancelled
                if (isActive) {
                    updateState(State.IDLE)
                    onSpeakComplete?.invoke()
                }
            } catch (e: CancellationException) {
                // Normal cancellation — stop() was called
                Log.d(TAG, "Speech cancelled")
                updateState(State.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Synthesis/playback error", e)
                updateState(State.IDLE)
                withContext(Dispatchers.Main) {
                    onError?.invoke("TTS error: ${e.message}")
                }
            }
        }
    }

    /**
     * Immediately stop any ongoing speech synthesis and playback.
     * This is how STT preemption works: call stop() before starting STT.
     *
     * Safe to call at any time — no-op if not speaking.
     */
    fun stop() {
        if (state == State.SPEAKING) {
            stopInternal()
            updateState(State.IDLE)
        }
    }

    /**
     * Aggressively unload the engine from RAM without destroying the manager's coroutine scope.
     * This allows STT to free memory while letting TTS re-initialize later.
     */
    fun unloadModel() {
        stopInternal()
        engine.shutdown()
        state = State.UNINITIALIZED
    }

    /**
     * Release all resources permanently. Call in Activity.onDestroy().
     */
    fun release() {
        unloadModel()
        scope.cancel()
    }

    // ── Internal helpers ──

    private fun stopInternal() {
        speakJob?.cancel()
        speakJob = null

        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) {
            // AudioTrack may already be stopped
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
        audioTrack = null
    }

    private fun createAudioTrack(): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(
            KittenTTSEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(KittenTTSEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun updateState(newState: State) {
        state = newState
        // Ensure callback fires on Main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged?.invoke(newState)
        } else {
            scope.launch(Dispatchers.Main) {
                onStateChanged?.invoke(newState)
            }
        }
    }
}
