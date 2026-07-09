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

        // Set up TTS callbacks for UI updates
        ttsManager.onStateChanged = { state ->
            when (state) {
                TextToSpeechManager.State.SPEAKING -> {
                    speakerButton.text = "STOP SPEAKING"
                    speakerButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark)
                }
                TextToSpeechManager.State.IDLE -> {
                    speakerButton.text = "SPEAKER"
                    speakerButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
                }
                else -> { /* no UI change for LOADING/UNINITIALIZED */ }
            }
        }

        ttsManager.onSpeakComplete = {
            statusTextView.text = "Status: Ready"
        }

        ttsManager.onError = { error ->
            statusTextView.text = "Status: TTS Error"
            resultTextView.text = error
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            setupManagers()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Initialize both STT and TTS managers.
     */
    private fun setupManagers() {
        statusTextView.text = "Status: Initializing..."

        lifecycleScope.launch {
            // Initialize STT (Whisper)
            try {
                sttManager.initialize("ggml-tiny.en-q5_1.bin")
                statusTextView.text = "Status: Whisper Ready! Loading TTS..."
            } catch (e: Exception) {
                statusTextView.text = "Status: STT Init Error!"
                resultTextView.text = "Error loading Whisper model.\n\nDetails: ${e.message}"
                e.printStackTrace()
            }

            // Initialize TTS (KittenTTS)
            try {
                ttsManager.initialize()
                statusTextView.text = "Status: Ready (STT + TTS)"
                speakerButton.isEnabled = true
            } catch (e: Exception) {
                // TTS failure is non-fatal — STT can still work
                statusTextView.text = "Status: Ready (STT only, TTS failed)"
                resultTextView.text = "TTS model not loaded. Make sure model files are in assets/model/.\n\nDetails: ${e.message}"
                e.printStackTrace()
            }

            recordButton.isEnabled = true
            setupUIListeners()
        }
    }

    private var isRecordingUiState = false

    private fun setupUIListeners() {
        // ── STT: Hold to Talk button ──
        recordButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // ★ AGGRESSIVE RAM UNLOADING: Destroy the TTS engine from memory 
                    // before Whisper starts allocating its massive matrices.
                    // We use unloadModel() to keep the manager's scope alive for later!
                    ttsManager.unloadModel()

                    // We fixed the massive RAM bottleneck by unloading TTS.
                    // Whisper now has the CPU power to run the real-time sliding window!
                    val started = sttManager.startListening { partialText ->
                        // Live update!
                        resultTextView.text = partialText
                    }
                    if (started) {
                        isRecordingUiState = true
                        statusTextView.text = "Status: Recording..."
                        resultTextView.text = "Listening..."
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
                            statusTextView.text = "Status: Ready"
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

        // ── TTS: Speaker button ──
        speakerButton.setOnClickListener {
            // ★ Mutual exclusivity: Don't speak if STT is active
            if (sttManager.state == SpeechToTextManager.State.RECORDING || 
                sttManager.state == SpeechToTextManager.State.TRANSCRIBING) {
                Toast.makeText(this, "Speech-to-Text is currently active. Please wait.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ttsManager.state == TextToSpeechManager.State.SPEAKING) {
                // If already speaking, stop
                ttsManager.stop()
                statusTextView.text = "Status: Ready"
            } else {
                val textToSpeak = resultTextView.text.toString()
                if (textToSpeak.isBlank()
                    || textToSpeak == "Listening..."
                    || textToSpeak == "Recording audio..."
                    || textToSpeak == "Processing speech to text..."
                    || textToSpeak.startsWith("(")
                    || textToSpeak.startsWith("Error")
                    || textToSpeak.startsWith("TTS model")) {
                    
                    // Demo text if no transcription result available
                    speakText("""{"text":"Hello! KittenTTS is working. Try recording some speech first, then press this button to hear it spoken back.","voice":"Hugo","speed":1.0}""")
                } else {
                    speakText("""{"text":"$textToSpeak","voice":"Hugo","speed":1.0}""")
                }
            }
        }
    }

    private fun speakText(json: String) {
        if (ttsManager.state == TextToSpeechManager.State.UNINITIALIZED) {
            statusTextView.text = "Status: Loading TTS Model into RAM..."
            speakerButton.isEnabled = false
            
            lifecycleScope.launch {
                try {
                    ttsManager.initialize()
                    statusTextView.text = "Status: Speaking..."
                    ttsManager.speak(json)
                } catch (e: Exception) {
                    statusTextView.text = "Status: TTS Load Failed"
                    Toast.makeText(this@MainActivity, "Failed to load TTS model", Toast.LENGTH_SHORT).show()
                } finally {
                    speakerButton.isEnabled = true
                }
            }
        } else {
            statusTextView.text = "Status: Speaking..."
            ttsManager.speak(json)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sttManager.release()
        ttsManager.release()
    }
}

