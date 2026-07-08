package com.example.localaudiototext

object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun initContext(modelPath: String): Long
    external fun fullTranscribe(contextPtr: Long, audioData: FloatArray): String
    external fun freeContext(contextPtr: Long)
}
