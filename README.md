# Speech-To-Text Backend Integration Documentation

This document provides **everything** a frontend developer needs to integrate the local Speech-To-Text (STT) backend into the main Android project. The backend uses **whisper.cpp** compiled natively via Android NDK and exposed to Kotlin through JNI.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────┐
│  Frontend (Your Code)                            │
│  ┌────────────────────────────────────────────┐  │
│  │  Mic Button  ──►  SpeechToTextManager      │  │
│  │                   ├─ startListening()       │  │
│  │                   ├─ stopListening(callback)│  │
│  │                   └─ release()              │  │
│  └────────────┬───────────────────────────────┘  │
│               │                                  │
│  ─────────────┼──────────────────────────────────│
│               ▼                                  │
│  Backend (This Module)                           │
│  ┌────────────────────────────────────────────┐  │
│  │  SpeechToTextManager.kt (facade)           │  │
│  │    ├─ AudioRecorder.kt (mic capture)       │  │
│  │    └─ WhisperLib.kt (JNI bridge)           │  │
│  │          └─ whisper_jni.cpp (C++ native)    │  │
│  │               └─ whisper.cpp (inference)    │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

**Data Flow:** Mic Button pressed → `AudioRecorder` captures 16kHz mono PCM audio → User releases button → PCM data sent to `whisper.cpp` via JNI → Transcribed text returned to callback → Frontend pastes text into search bar.

---

## 1. Files to Copy Into Your Project

Copy these files and directories **exactly** into your main project, preserving the folder structure:

### Native C++ Layer
| Source Path | Description |
|---|---|
| `app/src/main/cpp/whisper/` | The entire cloned whisper.cpp repository (used as a CMake subdirectory) |
| `app/src/main/cpp/CMakeLists.txt` | CMake build config that compiles whisper.cpp and the JNI wrapper |
| `app/src/main/cpp/whisper_jni.cpp` | JNI bridge between Kotlin and the C++ whisper engine |

### Kotlin API Layer
| Source Path | Description |
|---|---|
| `app/src/main/java/com/example/localaudiototext/WhisperLib.kt` | Declares native JNI methods (`initContext`, `fullTranscribe`, `freeContext`) |
| `app/src/main/java/com/example/localaudiototext/AudioRecorder.kt` | Records microphone audio in 16kHz, 16-bit Mono PCM format |
| `app/src/main/java/com/example/localaudiototext/SpeechToTextManager.kt` | **The only class you interact with.** Facade that manages initialization, recording, and transcription. |

### Model File
| Source Path | Description |
|---|---|
| `app/src/main/assets/ggml-tiny.en.bin` | Pre-trained Whisper model (~75MB). **Must be present at build time.** |

> **⚠️ Important:** Do NOT copy `MainActivity.kt` or `activity_main.xml` — those are test UI files and are not part of the backend module.

> **⚠️ Package Name:** If your main project uses a different package name (e.g., `com.yourteam.mainapp`), you must update the package declarations in all three `.kt` files AND update the JNI function names in `whisper_jni.cpp` to match. See Section 8 for details.

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
}
```

### 2c. Dependencies
Add the coroutines dependency (if not already present):
```kotlin
dependencies {
    // ... existing deps ...
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

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

## 4. Public API Reference

The **only class** the frontend needs to use is `SpeechToTextManager`.

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
| `modelFileName` | `String` | Filename of the model in `assets/` folder (e.g., `"ggml-tiny.en.bin"`) |

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
| `false` | `RECORD_AUDIO` permission not granted |

| Behavior | Detail |
|---|---|
| **Throws** | `IllegalStateException` if `initialize()` was not called first |
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
| **After calling** | `startListening()` and `stopListening()` will no longer work. You must create a new `SpeechToTextManager` instance. |

---

## 5. Complete Integration Example

Below is a full, copy-pasteable example showing how to wire the mic button to the search bar.

```kotlin
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.localaudiototext.SpeechToTextManager
import kotlinx.coroutines.launch

class YourActivity : AppCompatActivity() {

    private lateinit var sttManager: SpeechToTextManager
    private var isSttReady = false

    // Step 1: Register permission launcher
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Mic permission is required for voice input", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.your_layout)

        val searchBar = findViewById<EditText>(R.id.search_bar)
        val micButton = findViewById<ImageButton>(R.id.mic_button)

        // Step 2: Request mic permission early
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Step 3: Initialize STT engine
        sttManager = SpeechToTextManager(this)
        lifecycleScope.launch {
            try {
                sttManager.initialize("ggml-tiny.en.bin")
                isSttReady = true
            } catch (e: Exception) {
                Toast.makeText(
                    this@YourActivity,
                    "Voice input unavailable: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Step 4: Wire mic button (Hold-to-Talk)
        micButton.setOnTouchListener { _, event ->
            if (!isSttReady) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val started = sttManager.startListening()
                    if (started) {
                        searchBar.hint = "Listening..."
                        // Optional: change mic button icon/color to indicate recording
                    } else {
                        Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    searchBar.hint = "Processing..."
                    sttManager.stopListening { text ->
                        if (text.isNotEmpty()) {
                            searchBar.setText(text)
                        } else {
                            searchBar.hint = "No speech detected. Try again."
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // Step 5: Clean up
    override fun onDestroy() {
        super.onDestroy()
        sttManager.release()
    }
}
```

---

## 6. Error Handling Guide

| Scenario | What Happens | How to Handle |
|---|---|---|
| Model file missing from `assets/` | `initialize()` throws `IllegalStateException` | Wrap in try/catch. Show error message. Disable mic button. |
| Model file corrupted | `initialize()` throws `IllegalStateException` | Re-download the model file |
| `RECORD_AUDIO` permission denied | `startListening()` returns `false` | Check return value. Prompt user to grant permission in Settings. |
| `startListening()` called before `initialize()` | Throws `IllegalStateException` | Always initialize first. Track readiness with a boolean flag (see example). |
| Very short recording (<0.5s) | `stopListening()` returns empty string `""` | Check for empty result. Show "No speech detected" hint. |
| Background noise / unclear speech | `stopListening()` returns garbled or partial text | Expected behavior. Consider showing a "Try again" option. |
| `stopListening()` called without `startListening()` | Returns empty string `""` | Safe to call — no crash. |

---

## 7. APK Size Impact

| Component | Size |
|---|---|
| `ggml-tiny.en.bin` model | ~75 MB |
| Native `.so` libraries (all 4 ABIs) | ~12 MB |
| Kotlin source files | Negligible |
| **Total added to APK** | **~87 MB** |

> **Tip:** If APK size is a concern, you can reduce `abiFilters` in `build.gradle.kts` to only `arm64-v8a` (covers 95%+ of modern devices), which cuts native library size to ~4 MB. You can also consider downloading the model at runtime instead of bundling it in assets.

---

## 8. Changing the Package Name

If your main project uses a different package (e.g., `com.yourteam.mainapp` instead of `com.example.localaudiototext`), you must update **two things**:

### 8a. Kotlin Files
Update the `package` declaration at the top of all three files:
- `WhisperLib.kt`
- `AudioRecorder.kt`
- `SpeechToTextManager.kt`

```kotlin
// Change FROM:
package com.example.localaudiototext

// Change TO:
package com.yourteam.mainapp
```

### 8b. JNI Function Names in `whisper_jni.cpp`
JNI uses a naming convention based on the full package path. You must rename every function:

```cpp
// Change FROM:
Java_com_example_localaudiototext_WhisperLib_initContext(...)
Java_com_example_localaudiototext_WhisperLib_fullTranscribe(...)
Java_com_example_localaudiototext_WhisperLib_freeContext(...)

// Change TO (example for com.yourteam.mainapp):
Java_com_yourteam_mainapp_WhisperLib_initContext(...)
Java_com_yourteam_mainapp_WhisperLib_fullTranscribe(...)
Java_com_yourteam_mainapp_WhisperLib_freeContext(...)
```

> **Rule:** Replace every `.` in the package name with `_` in the C++ function name.

---

## 9. Performance & Customization

| Setting | Current Value | Location | Notes |
|---|---|---|---|
| Language | `"en"` (English) | `whisper_jni.cpp`, line 42 | Change to `"auto"` for auto-detection, or a specific language code |
| Thread count | `4` | `whisper_jni.cpp`, line 43 | Increase for faster transcription on high-end devices (max = CPU cores) |
| Sampling strategy | `WHISPER_SAMPLING_GREEDY` | `whisper_jni.cpp`, line 38 | Greedy is fastest. Alternative: `WHISPER_SAMPLING_BEAM_SEARCH` for accuracy |
| Audio sample rate | `16000` Hz | `AudioRecorder.kt`, line 19 | **Do not change.** Whisper requires 16kHz. |
| Audio format | 16-bit Mono PCM | `AudioRecorder.kt`, lines 20-21 | **Do not change.** |

### Model Options
| Model | Size | Speed | Accuracy | Download Link |
|---|---|---|---|---|
| `ggml-tiny.en` | 75 MB | ~1s | Good for short queries | [Download](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin) |
| `ggml-base.en` | 142 MB | ~2-3s | Better accuracy | [Download](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin) |
| `ggml-small.en` | 466 MB | ~5-8s | High accuracy | [Download](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin) |
