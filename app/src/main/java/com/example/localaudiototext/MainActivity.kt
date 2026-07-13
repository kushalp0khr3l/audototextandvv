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

class MainActivity : AppCompatActivity() {

    private lateinit var sttManager: SpeechToTextManager
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var statusTextView: TextView
    private lateinit var resultTextView: TextView
    private lateinit var recordButton: Button
    private lateinit var speakerButton: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupManagers()
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
        speakerButton = findViewById(R.id.speakerButton)

        sttManager = SpeechToTextManager(this)
        ttsManager = TextToSpeechManager(this)

        // TTS callbacks for UI updates
        ttsManager.onStateChanged = { state ->
            when (state) {
                TextToSpeechManager.State.SPEAKING -> {
                    speakerButton.text = "STOP SPEAKING"
                    speakerButton.backgroundTintList =
                        ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark)
                }
                TextToSpeechManager.State.IDLE -> {
                    speakerButton.text = "SPEAKER"
                    speakerButton.backgroundTintList =
                        ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
                }
                else -> { /* no UI change for LOADING / UNINITIALIZED */ }
            }
        }

        ttsManager.onSpeakComplete = {
            runOnUiThread { statusTextView.text = "Status: Ready" }
        }

        ttsManager.onError = { error ->
            runOnUiThread {
                statusTextView.text = "Status: TTS Error"
                resultTextView.text = error
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupManagers()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Initialize both STT and TTS managers. Native APIs don't need heavy async loading —
     * TTS init callback fires quickly, STT is ready immediately.
     */
    private fun setupManagers() {
        statusTextView.text = "Status: Initializing TTS..."

        // TTS initializes asynchronously; its callback updates the state
        ttsManager.initialize()

        // STT via native SpeechRecognizer — available immediately after permission granted
        statusTextView.text = "Status: Ready"
        recordButton.isEnabled = true
        speakerButton.isEnabled = true

        setupUIListeners()
    }

    private var isRecordingUiState = false

    private fun setupUIListeners() {

        // ── STT: Hold to Talk ──
        recordButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Stop any TTS playback before recording
                    ttsManager.unloadModel()

                    val started = sttManager.startListening { partialText ->
                        runOnUiThread { resultTextView.text = partialText }
                    }

                    if (started) {
                        isRecordingUiState = true
                        statusTextView.text = "Status: Recording..."
                        resultTextView.text = "Listening..."
                        recordButton.text = "RELEASE TO TRANSCRIBE"
                        recordButton.backgroundTintList =
                            ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
                    } else {
                        Toast.makeText(this, "STT is busy. Please wait.", Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecordingUiState) {
                        isRecordingUiState = false
                        statusTextView.text = "Status: Transcribing..."
                        resultTextView.text = "Processing speech to text..."
                        recordButton.text = "HOLD TO TALK"
                        recordButton.backgroundTintList =
                            ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)

                        sttManager.stopListening { text ->
                            runOnUiThread {
                                statusTextView.text = "Status: Ready"
                                resultTextView.text = if (text.isNotEmpty()) text
                                else "(No speech detected)"
                            }
                        }
                    }
                    true
                }

                else -> false
            }
        }

        // ── TTS: Speaker button ──
        speakerButton.setOnClickListener {
            if (sttManager.state == SpeechToTextManager.State.RECORDING ||
                sttManager.state == SpeechToTextManager.State.TRANSCRIBING
            ) {
                Toast.makeText(this, "Speech-to-Text is active. Please wait.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ttsManager.state == TextToSpeechManager.State.SPEAKING) {
                ttsManager.stop()
                statusTextView.text = "Status: Ready"
            } else {
                val textToSpeak = resultTextView.text.toString()
                val isPlaceholder = textToSpeak.isBlank()
                    || textToSpeak == "Listening..."
                    || textToSpeak == "Processing speech to text..."
                    || textToSpeak.startsWith("(")
                    || textToSpeak.startsWith("Status:")

                val finalText = if (isPlaceholder)
                    "Hello! Android TTS is working. Try recording some speech first, then press this button to hear it spoken back."
                else
                    textToSpeak

                statusTextView.text = "Status: Speaking..."

                // Re-init TTS if it was stopped (e.g. after STT preemption)
                if (ttsManager.state == TextToSpeechManager.State.UNINITIALIZED) {
                    ttsManager.initialize()
                    // Speak once TTS fires IDLE state
                    ttsManager.onStateChanged = { state ->
                        when (state) {
                            TextToSpeechManager.State.IDLE -> {
                                runOnUiThread {
                                    speakerButton.text = "SPEAKER"
                                    speakerButton.backgroundTintList =
                                        ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
                                }
                                // Speak the first time we become IDLE after re-init
                                if (!hasSpokeAfterReinit) {
                                    hasSpokeAfterReinit = true
                                    ttsManager.speak(finalText)
                                }
                            }
                            TextToSpeechManager.State.SPEAKING -> {
                                runOnUiThread {
                                    speakerButton.text = "STOP SPEAKING"
                                    speakerButton.backgroundTintList =
                                        ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark)
                                }
                            }
                            else -> {}
                        }
                    }
                    hasSpokeAfterReinit = false
                } else {
                    ttsManager.speak(finalText)
                }
            }
        }
    }

    // Guard to avoid double-speaking on TTS re-init
    private var hasSpokeAfterReinit = false

    override fun onDestroy() {
        super.onDestroy()
        sttManager.release()
        ttsManager.release()
    }
}
