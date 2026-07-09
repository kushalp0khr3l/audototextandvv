package com.example.localaudiototext

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Represents a parsed NumPy array with float32 data and shape metadata.
 */
data class NpyArray(
    val data: FloatArray,
    val shape: IntArray
)

/**
 * Reader for NumPy .npz archive files (ZIP of .npy files).
 * Used to load KittenTTS voice style embeddings from voices.npz.
 *
 * Supports float32 ("<f4") arrays only — this is the only dtype
 * used by KittenTTS voice files.
 */
object NpzReader {

    /**
     * Read all float32 arrays from an NPZ (ZIP of NPY) input stream.
     * @return map of array name → NpyArray
     */
    fun readAllFloatArrays(inputStream: InputStream): Map<String, NpyArray> {
        val result = mutableMapOf<String, NpyArray>()
        val zipStream = ZipInputStream(inputStream)

        var entry = zipStream.nextEntry
        while (entry != null) {
            if (entry.name.endsWith(".npy")) {
                val name = entry.name.removeSuffix(".npy")
                val bytes = zipStream.readBytes()
                val array = parseNpy(bytes)
                result[name] = array
            }
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        zipStream.close()

        return result
    }

    /**
     * Parse a single .npy binary file.
     * Format: magic bytes + version + header length + header dict + raw data
     */
    private fun parseNpy(data: ByteArray): NpyArray {
        // Verify magic: \x93NUMPY
        require(data[0] == 0x93.toByte() && data[1] == 'N'.code.toByte() &&
                data[2] == 'U'.code.toByte() && data[3] == 'M'.code.toByte() &&
                data[4] == 'P'.code.toByte() && data[5] == 'Y'.code.toByte()) {
            "Invalid NPY magic bytes"
        }

        val majorVersion = data[6].toInt() and 0xFF

        // Read header length (varies by NPY version)
        val headerLen: Int
        val headerStart: Int
        if (majorVersion == 1) {
            headerLen = ByteBuffer.wrap(data, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            headerStart = 10
        } else {
            headerLen = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            headerStart = 12
        }

        val headerStr = String(data, headerStart, headerLen, Charsets.US_ASCII).trim()
        val dataOffset = headerStart + headerLen

        // Parse shape from header dict: {'descr': '<f4', 'fortran_order': False, 'shape': (400, 256), }
        val shape = parseShape(headerStr)
        val elementCount = shape.fold(1) { acc, dim -> acc * dim }

        // Parse float32 data (little-endian)
        val floats = FloatArray(elementCount)
        ByteBuffer.wrap(data, dataOffset, elementCount * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(floats)

        return NpyArray(floats, shape)
    }

    /**
     * Extract shape tuple from NPY header string.
     * Matches patterns like: 'shape': (400, 256) or 'shape': (400,)
     */
    private fun parseShape(headerStr: String): IntArray {
        val regex = Regex("""'shape':\s*\(([^)]*)\)""")
        val match = regex.find(headerStr) ?: return intArrayOf(0)
        val shapeStr = match.groupValues[1]

        return shapeStr.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
            .toIntArray()
    }
}
