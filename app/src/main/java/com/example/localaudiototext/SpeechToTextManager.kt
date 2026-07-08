package com.example.localaudiototext

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SpeechToTextManager(private val context: Context) {
    private val audioRecorder = AudioRecorder(context)
    private var whisperContextPtr: Long = 0L
    private val scope = CoroutineScope(Dispatchers.Main)

    suspend fun initialize(modelFileName: String) = withContext(Dispatchers.IO) {
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
    }

    fun startListening(): Boolean {
        if (whisperContextPtr == 0L) {
            throw IllegalStateException("Whisper context not initialized. Call initialize() first.")
        }
        return audioRecorder.startRecording(scope)
    }

    fun stopListening(onResult: (String) -> Unit) {
        scope.launch {
            val audioData = audioRecorder.stopAndGetAudio()
            if (audioData.isEmpty()) {
                onResult("")
                return@launch
            }
            
            val result = withContext(Dispatchers.Default) {
                WhisperLib.fullTranscribe(whisperContextPtr, audioData)
            }
            onResult(result.trim())
        }
    }

    fun release() {
        if (whisperContextPtr != 0L) {
            WhisperLib.freeContext(whisperContextPtr)
            whisperContextPtr = 0L
        }
    }
}
