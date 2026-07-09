package com.example.localaudiototext

/**
 * Maps IPA phoneme characters to integer token IDs for KittenTTS ONNX model input.
 * The symbol table must exactly match the Python TextCleaner from the KittenTTS reference.
 *
 * Symbol order: PAD + punctuation + ASCII letters + IPA extension characters
 */
object TextCleaner {

    private val symbolToIndex: Map<Char, Int>

    init {
        val pad = "\$"
        val punctuation = ";:,.!?\u00A1\u00BF\u2014\u2026\u201C\u00AB\u00BB\u201D\u201C "
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val lettersIpa = "\u0251\u0250\u0252\u00E6\u0253\u0299\u03B2\u0254\u0255\u00E7" +
                "\u0257\u0256\u00F0\u02A4\u0259\u0258\u025A\u025B\u025C\u025D" +
                "\u025E\u025F\u0284\u0261\u0260\u0262\u029B\u0266\u0267\u0127" +
                "\u0265\u029C\u0268\u026A\u029D\u026D\u026C\u026B\u026E\u029F" +
                "\u0271\u026F\u0270\u014B\u0273\u0272\u0274\u00F8\u0275\u0278" +
                "\u03B8\u0153\u0276\u0298\u0279\u027A\u027E\u027B\u0280\u0281" +
                "\u027D\u0282\u0283\u0288\u02A7\u0289\u028A\u028B\u2C71\u028C" +
                "\u0263\u0264\u028D\u03C7\u028E\u028F\u0291\u0290\u0292\u0294" +
                "\u02A1\u0295\u02A2\u01C0\u01C1\u01C2\u01C3\u02C8\u02CC\u02D0" +
                "\u02D1\u02BC\u02B4\u02B0\u02B1\u02B2\u02B7\u02E0\u02E4\u02DE" +
                "\u2193\u2191\u2192\u2197\u2198\u2018\u0329\u2019\u1D7B"

        val symbols = pad + punctuation + letters + lettersIpa
        val map = mutableMapOf<Char, Int>()
        for (i in symbols.indices) {
            map[symbols[i]] = i
        }
        symbolToIndex = map
    }

    /**
     * Convert a phoneme string to a list of token IDs.
     * Unknown characters are silently skipped (matching Python behavior).
     */
    fun cleanToTokens(text: String): List<Int> {
        val tokens = mutableListOf<Int>()
        for (char in text) {
            symbolToIndex[char]?.let { tokens.add(it) }
        }
        return tokens
    }
}
