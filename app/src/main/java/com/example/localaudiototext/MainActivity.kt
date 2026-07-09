package com.example.localaudiototext

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var sttManager: SpeechToTextManager
    private lateinit var statusTextView: TextView
    private lateinit var resultTextView: TextView
    private lateinit var recordButton: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupSttManager()
        } else {
            statusTextView.text = "Permission Denied: Cannot record audio."
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        resultTextView = findViewById(R.id.resultTextView)
        recordButton = findViewById(R.id.recordButton)

        sttManager = SpeechToTextManager(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            setupSttManager()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupSttManager() {
        statusTextView.text = "Status: Initializing Whisper..."
        
        lifecycleScope.launch {
            try {
                // Expecting ggml-tiny.en.bin to be in assets
                sttManager.initialize("ggml-tiny.en.bin")
                statusTextView.text = "Status: Whisper Ready!"
                recordButton.isEnabled = true
                setupUIListeners()
            } catch (e: Exception) {
                statusTextView.text = "Status: Initialization Error!"
                resultTextView.text = "Error loading model. Make sure you placed 'ggml-tiny.en.bin' in the app/src/main/assets folder.\n\nDetails: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    private var isRecordingUiState = false

    private fun setupUIListeners() {
        recordButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val started = sttManager.startListening()
                    if (started) {
                        isRecordingUiState = true
                        statusTextView.text = "Status: Recording..."
                        resultTextView.text = "Recording audio..."
                        recordButton.text = "RELEASE TO TRANSCRIBE"
                        recordButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
                    } else {
                        Toast.makeText(this, "Whisper is busy. Please wait.", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecordingUiState) {
                        isRecordingUiState = false
                        statusTextView.text = "Status: Transcribing..."
                        resultTextView.text = "Processing speech to text..."
                        recordButton.text = "HOLD TO TALK"
                        recordButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
                        
                        sttManager.stopListening { text ->
                            statusTextView.text = "Status: Whisper Ready!"
                            if (text.isNotEmpty()) {
                                resultTextView.text = text
                            } else {
                                resultTextView.text = "(No speech detected or whisper failed to transcribe)"
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sttManager.release()
    }
}
