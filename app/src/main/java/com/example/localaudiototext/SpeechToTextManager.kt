package com.example.localaudiototext

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class SpeechToTextManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToTextManager"
        private const val PARTIAL_INTERVAL_MS = 1000L  // Run partial transcription every 1 second
        private const val MIN_AUDIO_SAMPLES = 8000     // 0.5s at 16kHz — skip if too short
    }

    enum class State {
        UNINITIALIZED,
        IDLE,
        RECORDING,
        TRANSCRIBING
    }

    private val audioRecorder = AudioRecorder(context)
    private var whisperContextPtr: Long = 0L
    private val scope = CoroutineScope(Dispatchers.Main)

    // Real-time transcription state
    private var partialJob: Job? = null
    @Volatile private var isTranscribing = false

    var state = State.UNINITIALIZED
        private set

    suspend fun initialize(modelFileName: String) = withContext(Dispatchers.IO) {
        if (state != State.UNINITIALIZED) return@withContext
        
        val modelFile = File(context.filesDir, modelFileName)
        if (!modelFile.exists()) {
            context.assets.open(modelFileName).use { inputStream ->
                FileOutputStream(modelFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        
        whisperContextPtr = WhisperLib.initContext(modelFile.absolutePath)
        if (whisperContextPtr == 0L) {
            throw IllegalStateException("Failed to initialize whisper context")
        }
        state = State.IDLE
    }

    /**
     * Start recording and optionally receive real-time partial transcription results.
     *
     * @param onPartialResult Callback invoked on the Main thread with intermediate
     *                        transcription text every ~1 second. Pass null to disable
     *                        real-time updates (original batch-only behavior).
     * @return true if recording started successfully
     */
    fun startListening(onPartialResult: ((String) -> Unit)? = null): Boolean {
        if (state != State.IDLE) {
            return false
        }
        val started = audioRecorder.startRecording(scope)
        if (started) {
            state = State.RECORDING

            // Launch the real-time partial transcription loop if callback provided
            if (onPartialResult != null) {
                partialJob = scope.launch {
                    // Give the microphone a moment to capture initial audio
                    delay(PARTIAL_INTERVAL_MS)

                    while (state == State.RECORDING) {
                        if (!isTranscribing) {
                            isTranscribing = true
                            try {
                                val audio = withContext(Dispatchers.Default) {
                                    audioRecorder.getCurrentlyRecordedAudio(trim = true)
                                }
                                if (audio.size >= MIN_AUDIO_SAMPLES) {
                                    val partial = withContext(Dispatchers.Default) {
                                        WhisperLib.fullTranscribe(whisperContextPtr, audio)
                                    }
                                    if (state == State.RECORDING && partial.isNotBlank()) {
                                        onPartialResult(partial.trim())
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Partial transcription failed", e)
                            } finally {
                                isTranscribing = false
                            }
                        }
                        delay(PARTIAL_INTERVAL_MS)
                    }
                }
            }
        }
        return started
    }

    fun stopListening(onResult: (String) -> Unit) {
        if (state != State.RECORDING) {
            onResult("")
            return
        }
        
        // Cancel the partial transcription loop
        partialJob?.cancel()
        partialJob = null

        state = State.TRANSCRIBING
        scope.launch {
            // Wait for any in-flight partial transcription to finish
            while (isTranscribing) {
                delay(50)
            }

            val audioData = audioRecorder.stopAndGetAudio()
            if (audioData.isEmpty()) {
                state = State.IDLE
                onResult("")
                return@launch
            }
            
            val result = withContext(Dispatchers.Default) {
                WhisperLib.fullTranscribe(whisperContextPtr, audioData)
            }
            state = State.IDLE
            onResult(result.trim())
        }
    }

    fun release() {
        partialJob?.cancel()
        partialJob = null
        if (whisperContextPtr != 0L) {
            WhisperLib.freeContext(whisperContextPtr)
            whisperContextPtr = 0L
        }
        state = State.UNINITIALIZED
    }
}
