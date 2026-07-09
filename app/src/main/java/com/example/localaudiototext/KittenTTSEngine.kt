package com.example.localaudiototext

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Core KittenTTS synthesis engine.
 *
 * Pipeline: text → TextPreprocessor → EspeakPhonemizer → TextCleaner → ONNX inference → PCM16 audio
 *
 * Output format: 24kHz, mono, 16-bit signed PCM (little-endian).
 *
 * This class is NOT thread-safe for concurrent synthesis calls.
 * Use TextToSpeechManager for thread-safe, cancellable access.
 */
class KittenTTSEngine {

    companion object {
        private const val TAG = "KittenTTSEngine"
        const val SAMPLE_RATE = 24000
        private const val TRIM_SAMPLES = 0 // Reduced from 5000 to prevent cutting off the last syllable
        private const val MAX_CHUNK_LEN = 400
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var voices: Map<String, NpyArray> = emptyMap()
    private var voiceAliases: Map<String, String> = emptyMap()
    private var speedPriors: Map<String, Float> = emptyMap()
    private val preprocessor = TextPreprocessor(removePunctuation = false)
    private val phonemizer = EspeakPhonemizer()
    private var initialized = false

    /** Available voice names that can be passed to synthesize(). */
    val availableVoiceNames = listOf("Bella", "Jasper", "Luna", "Bruno", "Rosie", "Hugo", "Kiki", "Leo")

    /**
     * Load the ONNX model, voice embeddings, and initialize the phonemizer.
     * Must be called once before any synthesis. Safe to call multiple times (no-op after first).
     *
     * @throws RuntimeException if model files are missing or phonemizer fails
     */
    fun loadModel(context: Context) {
        if (initialized) return

        // Load config
        loadConfig(context)

        // Load voices
        loadVoices(context)

        // Initialize phonemizer
        phonemizer.initialize(context)

        // Fail-fast: verify phonemizer is working
        val testPhoneme = phonemizer.phonemize("test")
        if (testPhoneme.isBlank()) {
            throw RuntimeException("Phonemizer initialization failed: no output from test")
        }

        // Load ONNX model via file path (avoids AssetManager large file truncation issues)
        ortEnv = OrtEnvironment.getEnvironment()
        val modelFile = java.io.File(context.filesDir, "kitten_tts.onnx")
        if (!modelFile.exists()) {
            context.assets.open("model/kitten_tts.onnx").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        ortSession = ortEnv!!.createSession(modelFile.absolutePath)

        initialized = true
        Log.i(TAG, "KittenTTSEngine loaded: ${voices.size} voices, model ready")
    }

    /**
     * Check if the engine is initialized and ready for synthesis.
     */
    fun isReady(): Boolean = initialized

    private fun loadConfig(context: Context) {
        val json = context.assets.open("model/config.json").bufferedReader().use { it.readText() }
        val config = JSONObject(json)

        // Parse voice aliases
        val aliasesObj = config.optJSONObject("voice_aliases")
        if (aliasesObj != null) {
            val map = mutableMapOf<String, String>()
            for (key in aliasesObj.keys()) {
                map[key] = aliasesObj.getString(key)
            }
            voiceAliases = map
        }

        // Parse speed priors
        val priorsObj = config.optJSONObject("speed_priors")
        if (priorsObj != null) {
            val map = mutableMapOf<String, Float>()
            for (key in priorsObj.keys()) {
                map[key] = priorsObj.getDouble(key).toFloat()
            }
            speedPriors = map
        }
    }

    private fun loadVoices(context: Context) {
        voices = context.assets.open("model/voices.npz").use { NpzReader.readAllFloatArrays(it) }
        Log.i(TAG, "Loaded ${voices.size} voices: ${voices.keys}")
    }

    /**
     * Synthesize speech from text with callback-driven streaming.
     * Emits each chunk as soon as it's synthesized (true streaming, not buffering).
     *
     * @param text Text to synthesize
     * @param voiceName Voice name (default: Bella)
     * @param speed Synthesis speed multiplier (default: 1.0)
     * @param onChunkReady Callback invoked for each synthesized PCM chunk.
     *                     Return false from this callback to cancel remaining synthesis.
     */
    fun synthesizeStreaming(
        text: String,
        voiceName: String = "Bella",
        speed: Float = 1.0f,
        onChunkReady: (ByteArray) -> Boolean
    ) {
        check(initialized) { "Engine not initialized. Call loadModel() first." }

        val cleanText = preprocessor.process(text)
        val chunks = chunkText(cleanText, MAX_CHUNK_LEN)

        for (chunk in chunks) {
            val pcm = synthesizeChunk(chunk, voiceName, speed)
            if (pcm.isNotEmpty()) {
                val shouldContinue = onChunkReady(pcm)
                if (!shouldContinue) {
                    Log.d(TAG, "Synthesis cancelled by callback")
                    return
                }
            }
        }
    }

    /**
     * Synthesize speech from text (convenience method for full buffering).
     * Returns complete PCM16 byte array at 24kHz, mono.
     * For streaming synthesis, use synthesizeStreaming() instead.
     */
    fun synthesize(text: String, voiceName: String = "Bella", speed: Float = 1.0f): ByteArray {
        check(initialized) { "Engine not initialized. Call loadModel() first." }

        val buffer = ByteArrayOutputStream()
        synthesizeStreaming(text, voiceName, speed) { chunk ->
            buffer.write(chunk)
            true // continue
        }
        return buffer.toByteArray()
    }

    private fun synthesizeChunk(text: String, voiceName: String, speed: Float): ByteArray {
        // Resolve voice alias
        val voiceId = voiceAliases[voiceName] ?: voiceName

        // Apply speed prior
        val adjustedSpeed = speed * (speedPriors[voiceId] ?: 1.0f)

        // Phonemize
        val phonemes = phonemizer.phonemize(text)

        // Tokenize: split on whitespace/punctuation, rejoin with spaces
        val tokenized = basicEnglishTokenize(phonemes)
        val joined = tokenized.joinToString(" ")

        // Convert to token IDs
        val tokens = TextCleaner.cleanToTokens(joined).toMutableList()

        // Wrap with special tokens: [0] + tokens + [10, 0]
        tokens.add(0, 0)   // PAD start
        tokens.add(10)     // delimiter
        tokens.add(0)      // PAD end

        // Prepare input_ids tensor: [1, seq_len] int64
        val inputIds = LongArray(tokens.size) { tokens[it].toLong() }

        // Prepare voice style tensor: [1, 256]
        val voiceData = voices[voiceId]
            ?: throw IllegalArgumentException("Voice '$voiceId' not found. Available: ${voices.keys}")

        // ref_id = min(len(original_text), shape[0] - 1)
        val numStyles = voiceData.shape[0]  // 400
        val styleDim = voiceData.shape[1]   // 256
        val refId = minOf(text.length, numStyles - 1)
        val styleSlice = FloatArray(styleDim)
        System.arraycopy(voiceData.data, refId * styleDim, styleSlice, 0, styleDim)

        // Run ONNX inference
        val env = ortEnv!!
        val session = ortSession!!

        val inputIdsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(inputIds),
            longArrayOf(1, inputIds.size.toLong())
        )
        val styleTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(styleSlice),
            longArrayOf(1, styleDim.toLong())
        )
        val speedTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatArrayOf(adjustedSpeed)),
            longArrayOf(1)
        )

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "style" to styleTensor,
            "speed" to speedTensor
        )

        val results = session.run(inputs)

        // Get output audio (float32 array). ONNX Runtime may return either a flat FloatArray
        // or nested arrays depending on model output shape/runtime behavior.
        val outputTensor = results[0]
        val outputValue = outputTensor.value
        val audioData = when (outputValue) {
            is FloatArray -> outputValue
            is Array<*> -> {
                val first = outputValue.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is Array<*> -> {
                        val nestedFirst = first.firstOrNull()
                        if (nestedFirst is FloatArray) {
                            nestedFirst
                        } else {
                            throw RuntimeException("Unexpected nested ONNX output type: ${nestedFirst?.javaClass}")
                        }
                    }
                    else -> throw RuntimeException("Unexpected ONNX output element type: ${first?.javaClass}")
                }
            }
            else -> throw RuntimeException("Unexpected ONNX output value type: ${outputValue?.javaClass}")
        }
        Log.d(TAG, "ONNX output type=${outputValue?.javaClass}, samples=${audioData.size}")

        // Trim last 5000 samples (matching Python: audio[..., :-5000])
        val trimmedLength = maxOf(0, audioData.size - TRIM_SAMPLES)
        val trimmedAudio = FloatArray(trimmedLength)
        System.arraycopy(audioData, 0, trimmedAudio, 0, trimmedLength)

        // Convert float32 → PCM16 byte array
        val pcm = floatToPcm16(trimmedAudio)

        // Clean up tensors
        inputIdsTensor.close()
        styleTensor.close()
        speedTensor.close()
        results.close()

        return pcm
    }

    /**
     * Release all native resources (ONNX session, espeak-ng).
     */
    fun shutdown() {
        ortSession?.close()
        ortEnv?.close()
        phonemizer.shutdown()
        ortSession = null
        ortEnv = null
        initialized = false
    }

    // ── Utility functions ──

    /**
     * Split text into chunks of max length, breaking at sentence boundaries.
     */
    private fun chunkText(text: String, maxLen: Int): List<String> {
        val sentences = text.split(Regex("""[.!?]+"""))
        val chunks = mutableListOf<String>()

        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.length <= maxLen) {
                chunks.add(ensurePunctuation(trimmed))
            } else {
                // Split long sentences by words
                val words = trimmed.split(" ")
                var tempChunk = ""
                for (word in words) {
                    if (tempChunk.length + word.length + 1 <= maxLen) {
                        tempChunk = if (tempChunk.isEmpty()) word else "$tempChunk $word"
                    } else {
                        if (tempChunk.isNotEmpty()) {
                            chunks.add(ensurePunctuation(tempChunk.trim()))
                        }
                        tempChunk = word
                    }
                }
                if (tempChunk.isNotEmpty()) {
                    chunks.add(ensurePunctuation(tempChunk.trim()))
                }
            }
        }

        return chunks
    }

    /**
     * Ensure text ends with punctuation. If not, add a comma.
     */
    private fun ensurePunctuation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.last() in ".!?,;:") trimmed else "$trimmed,"
    }

    /**
     * Basic English tokenizer: split on whitespace and punctuation.
     */
    private fun basicEnglishTokenize(text: String): List<String> {
        return Regex("""\w+|[^\w\s]""").findAll(text).map { it.value }.toList()
    }

    /**
     * Convert float32 audio samples to PCM16 little-endian byte array.
     */
    private fun floatToPcm16(audio: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in audio) {
            // Clamp to [-1.0, 1.0] then scale to short range
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcmValue = (clamped * Short.MAX_VALUE).toInt().toShort()
            buffer.putShort(pcmValue)
        }
        return buffer.array()
    }
}
