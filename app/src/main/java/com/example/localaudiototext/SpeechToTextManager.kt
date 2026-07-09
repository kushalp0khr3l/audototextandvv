package com.example.localaudiototext

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SpeechToTextManager(private val context: Context) {
    enum class State {
        UNINITIALIZED,
        IDLE,
        RECORDING,
        TRANSCRIBING
    }

    private val audioRecorder = AudioRecorder(context)
    private var whisperContextPtr: Long = 0L
    private val scope = CoroutineScope(Dispatchers.Main)

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

    fun startListening(): Boolean {
        if (state != State.IDLE) {
            return false
        }
        val started = audioRecorder.startRecording(scope)
        if (started) {
            state = State.RECORDING
        }
        return started
    }

    fun stopListening(onResult: (String) -> Unit) {
        if (state != State.RECORDING) {
            onResult("")
            return
        }
        
        state = State.TRANSCRIBING
        scope.launch {
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
        if (whisperContextPtr != 0L) {
            WhisperLib.freeContext(whisperContextPtr)
            whisperContextPtr = 0L
        }
        state = State.UNINITIALIZED
    }
}

