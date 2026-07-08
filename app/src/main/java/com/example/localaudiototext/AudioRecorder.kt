package com.example.localaudiototext

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

class AudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val audioData = mutableListOf<Short>()
    private var recordingJob: Job? = null

    // Whisper expects 16kHz, mono, 16-bit PCM
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        audioData.clear()
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true
        
        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(audioData) {
                        for (i in 0 until read) {
                            audioData.add(buffer[i])
                        }
                    }
                }
            }
        }
        return true
    }

    suspend fun stopAndGetAudio(): FloatArray {
        isRecording = false
        recordingJob?.join()
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val floats = synchronized(audioData) {
            FloatArray(audioData.size) { i ->
                audioData[i] / 32768.0f
            }
        }
        return floats
    }
}
