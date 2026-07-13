# Speech-To-Text & Text-To-Speech Integration Documentation

This document provides everything a frontend developer needs to integrate the native Android STT and TTS backends into the main app. Both engines use **built-in Android APIs** — no NDK, no model files, no extra dependencies required.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│  Frontend (Your Code)                                    │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Mic Button  ──►  SpeechToTextManager              │  │
│  │                   ├─ startListening(onPartial)     │  │
│  │                   ├─ stopListening(onResult)       │  │
│  │                   └─ release()                     │  │
│  │                                                    │  │
│  │  Speaker Btn ──►  TextToSpeechManager              │  │
│  │                   ├─ initialize()                  │  │
│  │                   ├─ speak(text)                   │  │
│  │                   ├─ stop()                        │  │
│  │                   └─ release()                     │  │
│  └────────────┬───────────────┬───────────────────────┘  │
│               │               │                          │
│  ─────────────┼───────────────┼──────────────────────────│
│               ▼               ▼                          │
│  Android System APIs                                     │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ SpeechRecognizer │  │ android.speech.tts           │   │
│  │ (offline STT)    │  │ TextToSpeech (offline TTS)  │   │
│  └──────────────────┘  └─────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

**STT Data Flow:** Mic button held → `SpeechRecognizer` captures audio → partial results streamed live → user releases → final text delivered via callback. Auto-restarts silently on silence to support long speech.

**TTS Data Flow:** `speak(text)` called → native Android TTS engine synthesizes on-device → plays through speakers → `onSpeakComplete` fires.

**Priority Rule:** Call `ttsManager.unloadModel()` (stops playback) before starting STT. STT always takes priority.

---

## 1. Files to Copy Into Your Project

Only **2 Kotlin files** are needed. No native libraries, no model assets.

| File | Package | Description |
|---|---|---|
| `SpeechToTextManager.kt` | `com.example.localaudiototext` | STT facade — wraps `android.speech.SpeechRecognizer` |
| `TextToSpeechManager.kt` | `com.example.localaudiototext` | TTS facade — wraps `android.speech.tts.TextToSpeech` |

> **Update the package declaration** at the top of each file to match your project's package name.

---

## 2. Build Configuration

### Dependencies
No extra dependencies are required. Both `android.speech.SpeechRecognizer` and `android.speech.tts.TextToSpeech` are part of the Android SDK and available on all devices API 26+.

Your `app/build.gradle.kts` only needs the standard Android dependencies:

```kotlin
dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
```

### Minimum SDK
```kotlin
android {
    minSdk = 26  // Android 8.0+ — required
}
```

> **No NDK, no CMake, no ONNX Runtime, no external model files needed.**

---

## 3. Permissions

### AndroidManifest.xml
Add the microphone permission:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### Runtime Permission Request
Android 6.0+ requires asking the user at runtime. Request this **before** calling `startListening()`. If not granted, `startListening()` returns `false`.

```kotlin
val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { isGranted ->
    if (isGranted) {
        // Permission granted — STT is now usable
    } else {
        // Show message to user
    }
}

// In onCreate or on first mic button interaction:
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
}
```

---

## 4. STT Public API Reference

The only class the frontend needs for STT is `SpeechToTextManager`.

### Constructor

```kotlin
val sttManager = SpeechToTextManager(context)
```

| Parameter | Type | Description |
|---|---|---|
| `context` | `Context` | Android Context (Activity or Application) |

**Lifecycle:** Create one instance per Activity/Fragment. The `SpeechRecognizer` is initialized immediately in the constructor — no `initialize()` call needed.

---

### `fun startListening(onPartialResult: ((String) -> Unit)? = null): Boolean`

Begins listening to the microphone. Internally handles auto-restart when the engine stops due to silence, so speech is accumulated continuously for as long as the user holds the button.

| Parameter | Type | Description |
|---|---|---|
| `onPartialResult` | `((String) -> Unit)?` | Called on Main thread with live partial text as the user speaks. Pass `null` to disable live updates. |

| Return Value | Meaning |
|---|---|
| `true` | Listening started successfully |
| `false` | `RECORD_AUDIO` permission not granted, or already recording |

**Example:**
```kotlin
val started = sttManager.startListening { partialText ->
    myTextView.text = partialText  // Live update — already on Main thread
}
```

---

### `fun stopListening(onResult: (String) -> Unit)`

Stops the microphone and delivers the full accumulated transcription.

| Parameter | Type | Description |
|---|---|---|
| `onResult` | `(String) -> Unit` | Called on Main thread with the complete transcribed text |

| Behavior | Detail |
|---|---|
| **Callback thread** | Main thread — safe to update UI directly |
| **Empty result** | Returns `""` if nothing was captured and no partial text was seen |
| **Partial fallback** | If the engine fires `ERROR_NO_MATCH`, the last partial text is returned instead of empty |

**Example:**
```kotlin
sttManager.stopListening { text ->
    if (text.isNotEmpty()) {
        mySearchBar.setText(text)
    } else {
        showToast("No speech detected")
    }
}
```

---

### `val state: State`

Observable current state of the STT manager.

| Value | Meaning |
|---|---|
| `UNINITIALIZED` | Manager has been released |
| `IDLE` | Ready to start listening |
| `RECORDING` | Actively listening |
| `TRANSCRIBING` | Processing after `stopListening()` called |

```kotlin
if (sttManager.state == SpeechToTextManager.State.RECORDING) {
    // Don't start TTS while recording
}
```

---

### `fun release()`

Destroys the underlying `SpeechRecognizer`. **Must be called in `onDestroy()`**.

```kotlin
override fun onDestroy() {
    super.onDestroy()
    sttManager.release()
}
```

---

## 5. TTS Public API Reference

The only class the frontend needs for TTS is `TextToSpeechManager`.

### Constructor

```kotlin
val ttsManager = TextToSpeechManager(context)
```

---

### `fun initialize()`

Initializes the native Android `TextToSpeech` engine asynchronously. Must be called before `speak()`. Safe to call multiple times — ignored if already initialized.

| Behavior | Detail |
|---|---|
| **Async** | Returns immediately; engine is ready shortly after |
| **Language** | Defaults to US English (`Locale.US`) |
| **On failure** | `onError` callback fires |

**Note:** Unlike the old Whisper/KittenTTS version, this does **not** need to be called from a coroutine. It's a regular function.

```kotlin
// In onCreate or setupManagers():
ttsManager.initialize()
```

---

### `fun speak(text: String)`

Synthesizes and plays the given text aloud using the device's installed TTS voice.

| Parameter | Type | Description |
|---|---|---|
| `text` | `String` | Plain text to speak. No JSON wrapping needed. |

| Behavior | Detail |
|---|---|
| **If already speaking** | Stops current speech, starts new one |
| **If not initialized** | Logs a warning, fires `onError` |
| **Blank text** | Silently ignored |

```kotlin
ttsManager.speak("Hello world")
```

> **Note from old API:** The old API required a JSON string like `{"text":"Hello","voice":"Hugo","speed":1.0}`. This is **no longer needed** — just pass plain text.

---

### Voice / Pitch / Speed Customization

The native TTS engine uses whatever voices are installed on the device. You can customize these properties directly on the `TextToSpeechManager`:

```kotlin
// Call these after initialize() fires (e.g., inside onStateChanged when state == IDLE)
ttsManager.setSpeechRate(0.9f)  // 1.0 = normal, 0.5 = slow, 2.0 = fast
ttsManager.setPitch(0.8f)       // 1.0 = normal, lower = deeper voice
```

> To add these methods, add them to `TextToSpeechManager.kt`:
> ```kotlin
> fun setSpeechRate(rate: Float) { tts?.setSpeechRate(rate) }
> fun setPitch(pitch: Float) { tts?.setPitch(pitch) }
> ```

---

### `fun stop()`

Immediately stop any ongoing speech.

```kotlin
ttsManager.stop()
```

Safe to call at any time — no-op if not speaking. State returns to `IDLE`.

---

### `fun unloadModel()`

Stops playback. With native TTS, no model needs unloading from RAM — this is a lightweight call that just calls `stop()` internally. It exists for API compatibility with the old Whisper/KittenTTS version.

**Still call this before starting STT** as a clean mutual-exclusivity pattern:

```kotlin
MotionEvent.ACTION_DOWN -> {
    ttsManager.unloadModel()  // Stop TTS if speaking
    sttManager.startListening { partial -> myTextView.text = partial }
}
```

---

### `fun release()`

Shuts down the TTS engine. **Must be called in `onDestroy()`**.

---

### State & Callbacks

```kotlin
// Observable state
ttsManager.state  // UNINITIALIZED, IDLE, LOADING, SPEAKING

// Callbacks — set these before calling initialize()
ttsManager.onStateChanged = { state ->
    when (state) {
        TextToSpeechManager.State.SPEAKING -> speakerButton.text = "STOP"
        TextToSpeechManager.State.IDLE     -> speakerButton.text = "SPEAK"
        else -> {}
    }
}
ttsManager.onSpeakComplete = {
    // Called on the TTS engine thread — use runOnUiThread {} if touching UI
    runOnUiThread { statusText.text = "Ready" }
}
ttsManager.onError = { errorMessage ->
    runOnUiThread { showToast(errorMessage) }
}
```

> **Important:** `onSpeakComplete` and `onError` may be called from a background thread. Always wrap UI updates in `runOnUiThread { }`.

---

## 6. Complete Integration Example

```kotlin
class YourActivity : AppCompatActivity() {

    private lateinit var sttManager: SpeechToTextManager
    private lateinit var ttsManager: TextToSpeechManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) setupManagers()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.your_layout)

        sttManager = SpeechToTextManager(this)
        ttsManager = TextToSpeechManager(this)

        // TTS callbacks
        ttsManager.onStateChanged = { state ->
            runOnUiThread {
                speakerButton.text = if (state == TextToSpeechManager.State.SPEAKING)
                    "STOP" else "SPEAK"
            }
        }
        ttsManager.onSpeakComplete = {
            runOnUiThread { statusText.text = "Ready" }
        }
        ttsManager.onError = { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }

        // Check/request mic permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            setupManagers()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupManagers() {
        ttsManager.initialize()  // Async — engine ready in ~100ms
        // STT is ready immediately after permission granted
    }

    private var isRecording = false

    private fun setupListeners() {
        // Hold to Talk
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ttsManager.unloadModel()  // Stop TTS if speaking

                    val started = sttManager.startListening { partialText ->
                        // Already on Main thread
                        resultTextView.text = partialText
                    }
                    if (started) {
                        isRecording = true
                        statusText.text = "Listening..."
                        micButton.text = "Release to Stop"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        isRecording = false
                        micButton.text = "Hold to Talk"
                        statusText.text = "Processing..."

                        sttManager.stopListening { text ->
                            // Already on Main thread
                            statusText.text = "Ready"
                            resultTextView.text = if (text.isNotEmpty()) text
                                                  else "(No speech detected)"
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Speak button
        speakerButton.setOnClickListener {
            if (sttManager.state == SpeechToTextManager.State.RECORDING) {
                Toast.makeText(this, "STT is active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ttsManager.state == TextToSpeechManager.State.SPEAKING) {
                ttsManager.stop()
            } else {
                val text = resultTextView.text.toString()
                if (text.isNotBlank()) ttsManager.speak(text)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sttManager.release()
        ttsManager.release()
    }
}
```

---

## 7. Offline Support

| Feature | Works Offline? | Notes |
|---|---|---|
| **TTS** | ✅ Always | Device TTS engine synthesizes entirely on-device |
| **STT** | ✅ On most modern devices | `EXTRA_PREFER_OFFLINE = true` is set. Requires offline language pack to be downloaded (usually pre-installed on Android 11+ and Pixel devices) |
| **STT fallback** | ⚠️ Some older devices | May require internet on devices without offline language pack installed |

**To enable offline STT on a device:**
Go to **Settings → General Management → Language → Text-to-speech / Speech Recognition** and download the English offline language model.

---

## 8. Error Handling

| Scenario | Behavior | How to Handle |
|---|---|---|
| `RECORD_AUDIO` denied | `startListening()` returns `false` | Check return value; prompt user to grant permission |
| STT not available on device | `startListening()` returns `false`, logs error | Show message; STT unavailable on this device |
| Silence / no speech | `stopListening()` returns `""` or last partial | Show "No speech detected" hint |
| TTS language not supported | `onError` fires during `initialize()` | Prompt user to install English TTS voice data in device settings |
| TTS called before `initialize()` | `onError` fires | Always call `initialize()` in `onCreate` or `setupManagers()` before calling `speak()` |
| Speech cut short on button release | Last partial text is returned as fallback | Handled automatically by `SpeechToTextManager` |

---

## 9. Comparison with Previous Implementation

| Aspect | Old (Whisper + KittenTTS) | New (Native Android APIs) |
|---|---|---|
| APK size overhead | ~88 MB | **~0 MB** |
| RAM usage | ~150-200 MB | **~5 MB** |
| Setup complexity | NDK, CMake, C++, model downloads | **None** |
| Offline support | ✅ Always | ✅ On most modern devices |
| TTS voice options | 8 fixed voices (Bella, Hugo, etc.) | All voices installed on device |
| STT input format | Raw PCM → Whisper | Native `SpeechRecognizer` |
| TTS input format | JSON `{"text":"..","voice":".."}` | **Plain string** |
| Init time | ~3-5 seconds | **~100ms** |
| Kotlin files needed | 8 files | **2 files** |

---

## 10. Package Name Migration

If your main project uses a different package (e.g., `com.yourteam.mainapp`):

Update the `package` declaration at the top of both files:

```kotlin
// SpeechToTextManager.kt
package com.yourteam.mainapp  // ← change this

// TextToSpeechManager.kt
package com.yourteam.mainapp  // ← change this
```

No JNI or C++ changes are needed — there is no native layer anymore.
