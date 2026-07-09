package com.example.localaudiototext

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Kotlin wrapper around the native espeak-ng JNI phonemizer.
 * Converts English text to IPA phoneme strings for KittenTTS token encoding.
 *
 * Usage:
 *   val phonemizer = EspeakPhonemizer()
 *   phonemizer.initialize(context)          // extracts data + loads native lib
 *   val ipa = phonemizer.phonemize("Hello") // → IPA string
 *   phonemizer.shutdown()                   // cleanup
 */
class EspeakPhonemizer {

    companion object {
        private const val TAG = "EspeakPhonemizer"

        init {
            System.loadLibrary("kittentts-native")
        }
    }

    private var initialized = false

    private external fun nativeInitialize(dataPath: String): Int
    private external fun nativeTextToPhonemes(text: String): String
    private external fun nativeTerminate()

    /**
     * Initialize the espeak-ng phonemizer.
     * Extracts espeak-ng-data from assets to internal storage on first run.
     *
     * @return true if initialization succeeded
     */
    fun initialize(context: Context): Boolean {
        if (initialized) return true

        val dataDir = extractEspeakData(context)
        if (dataDir == null) {
            Log.e(TAG, "Failed to extract espeak-ng data")
            return false
        }

        // espeak expects the parent directory of espeak-ng-data
        val parentPath = dataDir.parentFile?.absolutePath ?: dataDir.absolutePath
        val result = nativeInitialize(parentPath)
        if (result > 0) {
            initialized = true
            Log.i(TAG, "espeak-ng initialized, sample rate: $result")
            return true
        }

        Log.e(TAG, "espeak-ng initialization failed: $result")
        return false
    }

    /**
     * Convert text to IPA phonemes.
     * @throws IllegalStateException if not initialized
     */
    fun phonemize(text: String): String {
        check(initialized) { "EspeakPhonemizer not initialized. Call initialize() first." }
        return nativeTextToPhonemes(text)
    }

    /**
     * Release native espeak-ng resources.
     */
    fun shutdown() {
        if (initialized) {
            nativeTerminate()
            initialized = false
        }
    }

    /**
     * Extract espeak-ng-data from APK assets to app internal storage.
     * Skips extraction if already done (persists across app launches).
     */
    private fun extractEspeakData(context: Context): File? {
        val dataDir = File(context.filesDir, "espeak-ng-data")

        // Skip extraction if already done
        if (dataDir.exists() && dataDir.listFiles()?.isNotEmpty() == true) {
            return dataDir
        }

        return try {
            dataDir.mkdirs()
            copyAssetDir(context, "espeak-ng-data", dataDir)
            Log.i(TAG, "espeak-ng data extracted to ${dataDir.absolutePath}")
            dataDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract espeak-ng data", e)
            null
        }
    }

    /**
     * Recursively copy an asset directory to a filesystem directory.
     */
    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assets = context.assets
        val files = assets.list(assetPath) ?: return

        if (files.isEmpty()) {
            // It's a file, copy it
            assets.open(assetPath).use { input ->
                File(destDir, "").also { it.parentFile?.mkdirs() }
                destDir.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory, recurse
            destDir.mkdirs()
            for (file in files) {
                copyAssetDir(context, "$assetPath/$file", File(destDir, file))
            }
        }
    }
}
