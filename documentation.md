# Speech-To-Text Backend Integration Documentation

This document provides **everything** a frontend developer needs to integrate the local Speech-To-Text (STT) and Text-To-Speech (TTS) backends into the main Android project. The STT backend uses **whisper.cpp** and the TTS backend uses **KittenTTS** (ONNX), both compiled natively via Android NDK.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│  Frontend (Your Code)                                    │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Mic Button  ──►  SpeechToTextManager (STT)        │  │
│  │                   ├─ startListening()              │  │
│  │                   ├─ stopListening(callback)       │  │
│  │                   └─ release()                     │  │
│  │                                                    │  │
│  │  Speaker Btn ──►  TextToSpeechManager (TTS)        │  │
│  │                   ├─ speak(json)                   │  │
│  │                   ├─ stop()       ◄── STT cancels  │  │
│  │                   └─ release()         TTS here    │  │
│  └────────────┬───────────────┬───────────────────────┘  │
│               │               │                          │
│  ─────────────┼───────────────┼──────────────────────────│
│               ▼               ▼                          │
│  Backend (This Module)                                   │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ STT Pipeline     │  │ TTS Pipeline                │   │
│  │ SpeechToText     │  │ TextToSpeechManager.kt      │   │
│  │ Manager.kt       │  │  ├─ KittenTTSEngine.kt      │   │
│  │  ├─ AudioRec.kt  │  │  │   ├─ TextPreprocessor.kt │   │
│  │  └─ WhisperLib   │  │  │   ├─ EspeakPhonemizer.kt │   │
│  │     └─ JNI(C++)  │  │  │   ├─ TextCleaner.kt      │   │
│  │                   │  │  │   └─ NpzReader.kt        │   │
│  │                   │  │  └─ AudioTrack (playback)   │   │
│  └──────────────────┘  └─────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

**STT Data Flow:** Mic Button pressed → `AudioRecorder` captures 16kHz mono PCM audio → User releases button → PCM data sent to `whisper.cpp` via JNI → Transcribed text returned to callback.

**TTS Data Flow:** Speaker Button pressed → JSON parsed → `TextPreprocessor` normalizes text → `EspeakPhonemizer` converts to IPA → `TextCleaner` maps to token IDs → ONNX model generates audio → `AudioTrack` plays 24kHz PCM.

**Priority Rule:** When STT is requested while TTS is playing, TTS is **immediately cancelled** (coroutine cancellation + AudioTrack stop). STT always takes priority.

---

## 1. Files to Copy Into Your Project

Copy these files and directories **exactly** into your main project, preserving the folder structure:

### Native C++ Layer (STT)
| Source Path | Description |
|---|---|
| `app/src/main/cpp/whisper/` | The entire cloned whisper.cpp repository (used as a CMake subdirectory) |
| `app/src/main/cpp/CMakeLists.txt` | CMake build config that compiles whisper.cpp, JNI wrapper, AND espeak-ng |
| `app/src/main/cpp/whisper_jni.cpp` | JNI bridge between Kotlin and the C++ whisper engine |

### Native C++ Layer (TTS)
| Source Path | Description |
|---|---|
| `app/src/main/cpp/espeak-ng/` | Cloned espeak-ng source (used for phonemization) |
| `app/src/main/cpp/phonemize_jni.cpp` | JNI bridge for espeak-ng phonemization |

### Kotlin API Layer (STT)
| Source Path | Description |
|---|---|
| `WhisperLib.kt` | Declares native JNI methods (`initContext`, `fullTranscribe`, `freeContext`) |
| `AudioRecorder.kt` | Records microphone audio in 16kHz, 16-bit Mono PCM format |
| `SpeechToTextManager.kt` | **STT facade.** Manages initialization, recording, and transcription. |

### Kotlin API Layer (TTS)
| Source Path | Description |
|---|---|
| `EspeakPhonemizer.kt` | Kotlin wrapper around native espeak-ng JNI phonemizer |
| `TextPreprocessor.kt` | Text normalization (numbers→words, currency, time, etc.) |
| `TextCleaner.kt` | IPA phoneme → token ID mapping for ONNX model input |
| `NpzReader.kt` | NumPy `.npz` file parser for voice embeddings |
| `KittenTTSEngine.kt` | Core ONNX synthesis engine |
| `TextToSpeechManager.kt` | **TTS facade.** Manages model loading, synthesis, playback, and cancellation. |

### Model Files
| Source Path | Description |
|---|---|
| `app/src/main/assets/ggml-tiny.en-q5_1.bin` | Whisper STT model (~31 MB) |
| `app/src/main/assets/model/kitten_tts.onnx` | KittenTTS ONNX model (~24 MB) |
| `app/src/main/assets/model/voices.npz` | Voice style embeddings (~3 MB) |
| `app/src/main/assets/model/config.json` | Model configuration (<1 KB) |
| `app/src/main/assets/espeak-ng-data/` | Phoneme dictionaries (built from source) |

> **⚠️ Important:** Model files and espeak-ng data are NOT tracked in git. See Section 10 for download/build instructions.

---

## 2. Build Configuration Changes (build.gradle.kts)

You **must** apply the following changes to your main project's `app/build.gradle.kts`:

### 2a. Minimum compileSdk
```kotlin
android {
    compileSdk = 37  // Required by androidx.core:core-ktx:1.19.0
}
```

### 2b. NDK and CMake Configuration
Add these blocks inside the `android {}` block:

```kotlin
android {
    // ... existing config ...

    defaultConfig {
        // ... existing config ...

        // Add this block:
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11 -O3"
                // REQUIRED: Force Release mode for STT speed
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }
    }

    // Add these two blocks after compileOptions:
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "25.1.8937393"

    // Prevent compression of ONNX/NPZ assets (ONNX Runtime needs mmap)
    androidResources {
        noCompress += listOf("onnx", "npz")
    }
}
```

### 2c. Dependencies
If your project uses traditional `build.gradle.kts`, add these:
```kotlin
dependencies {
    // ... existing deps ...
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")
}
```
*(If using `libs.versions.toml`, declare them there instead and reference them as `libs.kotlinx.coroutines.android` and `libs.onnxruntime.android`.)*

### 2d. AGP 9+ Kotlin Configuration Warning (CRITICAL)
If your frontend project uses **Android Gradle Plugin (AGP) 9.0+** (which is common if you use Gradle 9.3+), AGP automatically registers the Kotlin extension. 

If you want to use **Kotlin 2.1.20**, **DO NOT** apply the `alias(libs.plugins.kotlin.android)` plugin directly inside your `app/build.gradle.kts`. This will cause a crash: `Cannot add extension with name 'kotlin'`.

**The correct fix:**
1. Define it in `gradle/libs.versions.toml`:
   ```toml
   [versions]
   kotlin = "2.1.20"
   [plugins]
   kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
   ```
2. Apply it **ONLY** to your root project's `build.gradle.kts` using `apply false` so it enters the classpath globally:
   ```kotlin
   plugins {
       alias(libs.plugins.android.application) apply false
       alias(libs.plugins.kotlin.android) apply false // <- Add here
   }
   ```
3. Let the `app/build.gradle.kts` module rely entirely on AGP to configure it implicitly. Do not list it in the app module's `plugins {}` block.

> **Note:** NDK 25.1.8937393 and CMake 3.22.1 will be auto-downloaded by Gradle on first build if not already installed.

---

## 3. Permissions

### Already Done (Manifest)
The `RECORD_AUDIO` permission has been added to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```
If your main project's manifest does not have this, add it.

### Frontend Must Handle: Runtime Permission Request
Android 6.0+ requires runtime permission. You **must** request this before calling `startListening()`. If the permission is not granted, `startListening()` will silently return `false`.

```kotlin
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// Register the launcher in your Activity/Fragment
val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { isGranted: Boolean ->
    if (isGranted) {
        // Permission granted — you can now use STT
    } else {
        // Permission denied — show a message to the user
    }
}

// Call this early (e.g., in onCreate or when mic button first appears)
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
}
```

---

## 4. STT Public API Reference

The **only class** the frontend needs for STT is `SpeechToTextManager`.

### Constructor

```kotlin
val sttManager = SpeechToTextManager(context)
```
| Parameter | Type | Description |
|---|---|---|
| `context` | `Context` | Android Context (Activity, Application, etc.) |

**Lifecycle:** Create one instance per Activity/Fragment. Do not create multiple instances.

---

### `suspend fun initialize(modelFileName: String)`

Copies the model from `assets/` to internal storage (first run only) and loads the Whisper C++ context into memory.

| Parameter | Type | Description |
|---|---|---|
| `modelFileName` | `String` | Filename of the model in `assets/` folder (e.g., `"ggml-tiny.en-q5_1.bin"`) |

| Behavior | Detail |
|---|---|
| **Runs on** | `Dispatchers.IO` (file I/O + native init) |
| **Must be called from** | A coroutine scope (`lifecycleScope.launch { ... }`) |
| **Throws** | `IllegalStateException` if native context creation fails (e.g., corrupt/missing model) |
| **Duration** | ~1-3 seconds on first run (file copy), <100ms on subsequent runs |

---

### `fun startListening(): Boolean`

Begins recording audio from the device microphone.

| Return Value | Meaning |
|---|---|
| `true` | Recording started successfully |
| `false` | `RECORD_AUDIO` permission not granted, or manager is busy (RECORDING/TRANSCRIBING) |

| Behavior | Detail |
|---|---|
| **Audio Format** | 16kHz sample rate, 16-bit, Mono PCM (required by Whisper) |
| **Recording** | Continues until `stopListening()` is called |

---

### `fun stopListening(onResult: (String) -> Unit)`

Stops recording, sends the captured audio to Whisper for transcription, and delivers the result.

| Parameter | Type | Description |
|---|---|---|
| `onResult` | `(String) -> Unit` | Callback invoked on the **Main Thread** with the transcribed text |

| Behavior | Detail |
|---|---|
| **Transcription runs on** | `Dispatchers.Default` (CPU-intensive) |
| **Callback runs on** | `Dispatchers.Main` (safe to update UI directly) |
| **Empty result** | Returns `""` if no speech detected, recording was too short, or transcription failed |
| **Processing time** | ~0.5-2 seconds for typical voice queries using the `tiny` model |

---

### `fun release()`

Frees the native Whisper context memory. **Must be called** when the Activity/Fragment is destroyed.

| Behavior | Detail |
|---|---|
| **Safe to call multiple times** | Yes, subsequent calls are no-ops |
| **After calling** | `startListening()` and `stopListening()` will no longer work. You must create a new instance. |

---

## 5. TTS Public API Reference

The **only class** the frontend needs for TTS is `TextToSpeechManager`.

### Constructor

```kotlin
val ttsManager = TextToSpeechManager(context)
```
| Parameter | Type | Description |
|---|---|---|
| `context` | `Context` | Android Context (Activity, Application, etc.) |

**Lifecycle:** Create one instance per Activity/Fragment. Do not create multiple instances.

---

### `suspend fun initialize()`

Loads the KittenTTS ONNX model, voice embeddings, and espeak-ng phonemizer.

| Behavior | Detail |
|---|---|
| **Runs on** | `Dispatchers.IO` |
| **Must be called from** | A coroutine scope (`lifecycleScope.launch { ... }`) |
| **Throws** | `RuntimeException` if model files are missing from `assets/model/` |
| **Duration** | ~2-5 seconds (ONNX model loading + espeak-ng data extraction on first run) |

---

### `fun speak(json: String)`

Synthesize and play speech from a JSON-formatted input string.

**JSON Input Format:**
```json
{
  "text": "The text to speak",
  "voice": "Bella",
  "speed": 1.0
}
```

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `text` | `String` | ✅ Yes | — | The text to convert to speech |
| `voice` | `String` | No | `"Bella"` | Voice name (see available voices below) |
| `speed` | `Number` | No | `1.0` | Speech speed multiplier (0.5 = slow, 2.0 = fast) |

| Behavior | Detail |
|---|---|
| **Synthesis runs on** | `Dispatchers.Default` (CPU-intensive ONNX inference) |
| **Playback runs on** | `Dispatchers.IO` (AudioTrack streaming) |
| **Output format** | 24kHz, mono, 16-bit PCM via `AudioTrack` |
| **If already speaking** | Previous speech is cancelled before starting new one |
| **If not initialized** | Call is ignored, `onError` callback fires |

**Available Voices:**

| Voice | Character |
|---|---|
| `Bella` | Default female voice |
| `Luna` | Female voice |
| `Rosie` | Female voice |
| `Kiki` | Female voice |
| `Jasper` | Male voice |
| `Bruno` | Male voice |
| `Hugo` | Male voice |
| `Leo` | Male voice |

---

### `fun stop()`

Immediately cancel any ongoing speech synthesis and playback.

| Behavior | Detail |
|---|---|
| **Cancellation** | Cancels the coroutine job, stops AudioTrack |
| **State after** | Returns to `IDLE` |
| **Safe to call anytime** | Yes — no-op if not speaking |

---

### `fun unloadModel()`

Aggressively unload the TTS engine from RAM without destroying its coroutine scope.

| Behavior | Detail |
|---|---|
| **Memory** | Frees up ~50MB of RAM by closing ONNX sessions |
| **Future Playback** | Safe! It will lazily reload the model automatically the next time `speak()` is called. |
| **Use Case** | **CRITICAL for STT.** Call this right before starting Whisper STT to prevent RAM swapping and massive latency on lower-end devices. |

---

### `fun release()`

Release all TTS resources permanently (ONNX session, espeak-ng, AudioTrack, CoroutineScope). **Must be called** in `onDestroy()`.

---

### State & Callbacks

```kotlin
// Observable state
ttsManager.state  // UNINITIALIZED, IDLE, LOADING, SPEAKING

// Callbacks (set these for UI updates)
ttsManager.onStateChanged = { state -> /* update button appearance */ }
ttsManager.onSpeakComplete = { /* speech finished naturally */ }
ttsManager.onError = { errorMsg -> /* show error to user */ }
```

---

## 6. STT ↔ TTS Priority Coordination & RAM Constraints

The rule is simple: **STT always takes priority over TTS, and they must not share memory.**

When the user presses the mic button to start recording:
1. Call `ttsManager.unloadModel()` — this cancels TTS and heavily clears RAM
2. Call `sttManager.startListening()` — Whisper begins allocating memory

```kotlin
// In your mic button handler:
MotionEvent.ACTION_DOWN -> {
    // ★ AGGRESSIVE RAM UNLOADING
    ttsManager.unloadModel()  
    val started = sttManager.startListening()
    // ... update UI ...
}
```

This prevents the Android OS from using zRAM compression during inference, which would cause severe real-time transcription latency.

---

## 7. Complete Integration Example (STT + TTS)

```kotlin
class YourActivity : AppCompatActivity() {

    private lateinit var sttManager: SpeechToTextManager
    private lateinit var ttsManager: TextToSpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.your_layout)

        sttManager = SpeechToTextManager(this)
        ttsManager = TextToSpeechManager(this)

        // Initialize both engines
        lifecycleScope.launch {
            sttManager.initialize("ggml-tiny.en-q5_1.bin")
            ttsManager.initialize()
        }

        // TTS callbacks
        ttsManager.onStateChanged = { state ->
            // Update speaker button appearance
        }
        ttsManager.onSpeakComplete = {
            // Speech finished
        }

        // Mic button (Hold to Talk)
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ttsManager.unloadModel()  // Aggressive RAM unload
                    sttManager.startListening()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    sttManager.stopListening { text ->
                        searchBar.setText(text)
                    }
                    true
                }
                else -> false
            }
        }

        // Speaker button
        speakerButton.setOnClickListener {
            val json = """{"text":"${searchBar.text}","voice":"Bella","speed":1.0}"""
            ttsManager.speak(json)
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

## 8. Error Handling Guide

| Scenario | What Happens | How to Handle |
|---|---|---|
| STT model file missing | `sttManager.initialize()` throws | Wrap in try/catch. Show error. Disable mic button. |
| TTS model files missing | `ttsManager.initialize()` throws | Wrap in try/catch. TTS failure is non-fatal — STT still works. |
| `ORT_INVALID_PROTOBUF` error | `ttsManager.initialize()` throws | The ONNX model file is corrupted (e.g. downloaded incorrectly via PowerShell) or truncated. Ensure you use `curl` to download. If you replace the file in `assets/`, you **must uninstall the app** to clear the old corrupted copy from internal storage (`filesDir`). |
| `RECORD_AUDIO` denied | `startListening()` returns `false` | Check return value. Prompt user. |
| TTS called before init | `speak()` ignored, `onError` fires | Check `ttsManager.state` before calling `speak()`. |
| Invalid JSON to `speak()` | `onError` callback fires | Validate JSON format. |
| Short recording (<0.5s) | `stopListening()` returns `""` | Show "No speech detected" hint. |

---

## 9. APK Size Impact

| Component | Size |
|---|---|
| `ggml-tiny.en-q5_1.bin` STT model | ~31 MB |
| `kitten_tts.onnx` TTS model | ~24 MB |
| `voices.npz` voice embeddings | ~3 MB |
| `espeak-ng-data/` phoneme data | ~2 MB |
| Native `.so` libraries (all ABIs) | ~20 MB |
| ONNX Runtime library | ~8 MB |
| Kotlin source files | Negligible |
| **Total added to APK** | **~88 MB** |

> **Tip:** Reduce `abiFilters` to only `arm64-v8a` to cut native library size significantly. Consider downloading models at runtime for smaller APK.

---

## 10. TTS Setup Instructions (Model & Data Files)

### Step 1: Download KittenTTS Model

```bash
mkdir -p app/src/main/assets/model
cd app/src/main/assets/model

> **⚠️ Windows Users:** Do NOT use PowerShell's `Invoke-WebRequest` to download these files, as it can corrupt the binary data resulting in an `ORT_INVALID_PROTOBUF` error. Use `curl` as shown below.

# Nano model (~24 MB, recommended for mobile)
curl -L https://huggingface.co/KittenML/kitten-tts-nano-0.8/resolve/main/kitten_tts_nano_v0_8.onnx -o kitten_tts.onnx
curl -L https://huggingface.co/KittenML/kitten-tts-nano-0.8/resolve/main/voices.npz -o voices.npz
curl -L https://huggingface.co/KittenML/kitten-tts-nano-0.8/resolve/main/config.json -o config.json

cd ../../../..
```

### Step 2: Clone espeak-ng Source

```bash
cd app/src/main/cpp
git clone --depth 1 https://github.com/espeak-ng/espeak-ng.git
cd ../../../..
```

### Step 3: Build espeak-ng Language Data (Requires Linux/WSL)

> **⚠️ Prerequisites:** You must have standard build tools installed in your Linux/WSL environment before running `cmake`. If you haven't already, run:
> ```bash
> sudo apt update
> sudo apt install build-essential
> ```

```bash
cd app/src/main/cpp/espeak-ng

cmake -Bbuild -DCMAKE_BUILD_TYPE=Release \
  -DUSE_MBROLA=OFF \
  -DUSE_LIBSONIC=OFF \
  -DUSE_LIBPCAUDIO=OFF \
  -DUSE_ASYNC=OFF \
  -DENABLE_TESTS=OFF

cmake --build build -j$(nproc)

# Copy required files to assets
ASSETS_DIR="../../assets/espeak-ng-data"
mkdir -p "$ASSETS_DIR/lang/gmw"

cp build/espeak-ng-data/phondata \
   build/espeak-ng-data/phonindex \
   build/espeak-ng-data/phontab \
   build/espeak-ng-data/phondata-manifest \
   build/espeak-ng-data/intonations \
   build/espeak-ng-data/en_dict \
   "$ASSETS_DIR/"

cp build/espeak-ng-data/lang/gmw/en* "$ASSETS_DIR/lang/gmw/"
cp -r build/espeak-ng-data/voices "$ASSETS_DIR/"

cd ../../../../..
```

**Expected files in `assets/espeak-ng-data/`:**
- `en_dict`, `phondata`, `phonindex`, `phontab`, `phondata-manifest`, `intonations`
- `voices/` directory
- `lang/gmw/en*` files

---

## 11. Changing the Package Name

If your main project uses a different package (e.g., `com.yourteam.mainapp`), you must update:

### 11a. Kotlin Files
Update the `package` declaration in ALL `.kt` files (both STT and TTS):
- `WhisperLib.kt`, `AudioRecorder.kt`, `SpeechToTextManager.kt`
- `EspeakPhonemizer.kt`, `TextPreprocessor.kt`, `TextCleaner.kt`, `NpzReader.kt`, `KittenTTSEngine.kt`, `TextToSpeechManager.kt`

### 11b. JNI Function Names
Update function names in BOTH JNI files:

**`whisper_jni.cpp`:**
```cpp
// Change FROM:
Java_com_example_localaudiototext_WhisperLib_initContext(...)
// Change TO:
Java_com_yourteam_mainapp_WhisperLib_initContext(...)
```

**`phonemize_jni.cpp`:**
```cpp
// Change FROM:
Java_com_example_localaudiototext_EspeakPhonemizer_nativeInitialize(...)
Java_com_example_localaudiototext_EspeakPhonemizer_nativeTextToPhonemes(...)
Java_com_example_localaudiototext_EspeakPhonemizer_nativeTerminate(...)
// Change TO:
Java_com_yourteam_mainapp_EspeakPhonemizer_nativeInitialize(...)
Java_com_yourteam_mainapp_EspeakPhonemizer_nativeTextToPhonemes(...)
Java_com_yourteam_mainapp_EspeakPhonemizer_nativeTerminate(...)
```

> **Rule:** Replace every `.` in the package name with `_` in the C++ function name.

---

## 12. Performance & Customization

### STT Settings
| Setting | Current Value | Location | Notes |
|---|---|---|---|
| Language | `"en"` (English) | `whisper_jni.cpp`, line 42 | Change to `"auto"` for auto-detection |
| Thread count | `2` | `whisper_jni.cpp`, line 43 | Prevents thermal throttling/OS freezing |
| Audio sample rate | `16000` Hz | `AudioRecorder.kt` | **Do not change.** Whisper requires 16kHz. |

### TTS Settings
| Setting | Current Value | Location | Notes |
|---|---|---|---|
| Default voice | `"Bella"` | `TextToSpeechManager.kt` | Changeable via JSON `voice` field |
| Default speed | `1.0` | `TextToSpeechManager.kt` | Changeable via JSON `speed` field |
| Audio sample rate | `24000` Hz | `KittenTTSEngine.kt` | **Do not change.** KittenTTS outputs 24kHz. |
| Max chunk length | `400` chars | `KittenTTSEngine.kt` | Longer text is split at sentence boundaries |

### STT Model Options
| Model | Size | Speed | Accuracy |
|---|---|---|---|
| `ggml-tiny.en` | 75 MB | ~1s | Good for short queries |
| `ggml-tiny.en-q5_1` | 31 MB | ~1s | Same accuracy, quantized (smaller) |
| `ggml-base.en` | 142 MB | ~2-3s | Better accuracy |

### TTS Model Options
| Model | Size | Parameters | Quality |
|---|---|---|---|
| `kitten-tts-nano-0.8` | ~24 MB | 15M | Good quality, fastest inference (recommended) |

