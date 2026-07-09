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
        if (isRecording) return false
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

    /**
     * Returns a snapshot of the currently recorded audio without stopping recording.
     * Used for real-time intermediate transcription while the user is still speaking.
     *
     * @param trim If true, applies VAD silence trimming to the snapshot
     */
    fun getCurrentlyRecordedAudio(trim: Boolean = true): FloatArray {
        val snapshot = synchronized(audioData) {
            audioData.toList()
        }
        return if (trim) trimSilence(snapshot) else toFloatArray(snapshot)
    }

    suspend fun stopAndGetAudio(): FloatArray {
        isRecording = false
        recordingJob?.join()
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val rawAudio = synchronized(audioData) {
            audioData.toList()
        }
        
        return trimSilence(rawAudio)
    }

    /**
     * Energy-based Voice Activity Detection (VAD).
     * Trims leading and trailing silence to significantly reduce STT processing time.
     */
    private fun trimSilence(audio: List<Short>): FloatArray {
        if (audio.isEmpty()) return FloatArray(0)

        val chunkSize = (sampleRate * 0.02).toInt() // 20ms chunks (320 samples)
        val threshold = 50.0 // RMS threshold (out of 32768)
        val padding = (sampleRate * 0.2).toInt() // 200ms padding

        var firstActive = -1
        var lastActive = -1

        // Scan for speech boundaries
        for (i in audio.indices step chunkSize) {
            val end = minOf(i + chunkSize, audio.size)
            var sumSquare = 0.0
            for (j in i until end) {
                val sample = audio[j].toDouble()
                sumSquare += sample * sample
            }
            val rms = Math.sqrt(sumSquare / (end - i))

            if (rms > threshold) {
                if (firstActive == -1) firstActive = i
                lastActive = end
            }
        }

        // If no speech detected, return empty
        if (firstActive == -1) return FloatArray(0)

        // Add padding
        val startIdx = maxOf(0, firstActive - padding)
        val endIdx = minOf(audio.size, lastActive + padding)

        val trimmedSize = endIdx - startIdx
        if (trimmedSize <= 0) return FloatArray(0)

        // Convert to FloatArray (-1.0 to 1.0) for Whisper
        return FloatArray(trimmedSize) { i ->
            audio[startIdx + i] / 32768.0f
        }
    }

    /** Convert raw Short samples to normalized FloatArray for Whisper. */
    private fun toFloatArray(audio: List<Short>): FloatArray {
        return FloatArray(audio.size) { i -> audio[i] / 32768.0f }
    }
}
