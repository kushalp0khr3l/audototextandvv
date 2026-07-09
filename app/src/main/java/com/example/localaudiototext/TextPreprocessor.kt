package com.example.localaudiototext

import java.text.Normalizer

/**
 * Comprehensive text preprocessing for TTS input.
 * Direct port of the Python TextPreprocessor from KittenTTS.
 * Converts numbers, currency, time, etc. to spoken word form.
 */
class TextPreprocessor(
    private val removePunctuation: Boolean = false
) {

    fun process(text: String): String {
        var t = text
        t = normalizeUnicode(t)
        t = removeHtmlTags(t)
        t = removeUrls(t)
        t = removeEmails(t)
        t = expandContractions(t)
        t = expandIpAddresses(t)
        t = normalizeLeadingDecimals(t)
        t = expandCurrency(t)
        t = expandPercentages(t)
        t = expandScientificNotation(t)
        t = expandTime(t)
        t = expandOrdinals(t)
        t = expandUnits(t)
        t = expandScaleSuffixes(t)
        t = expandFractions(t)
        t = expandDecades(t)
        t = expandPhoneNumbers(t)
        t = expandRanges(t)
        t = expandModelNames(t)
        t = replaceNumbers(t)
        if (removePunctuation) {
            t = removePunctuation(t)
        }
        t = t.lowercase()
        t = removeExtraWhitespace(t)
        return t
    }

    // ── Number → Words ──

    companion object {
        private val ONES = arrayOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
        )
        private val TENS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )
        private val SCALE = arrayOf("", "thousand", "million", "billion", "trillion")

        private val ORDINAL_EXCEPTIONS = mapOf(
            "one" to "first", "two" to "second", "three" to "third", "four" to "fourth",
            "five" to "fifth", "six" to "sixth", "seven" to "seventh", "eight" to "eighth",
            "nine" to "ninth", "twelve" to "twelfth"
        )

        private val CURRENCY_SYMBOLS = mapOf(
            "$" to "dollar", "\u20AC" to "euro", "\u00A3" to "pound", "\u00A5" to "yen",
            "\u20B9" to "rupee", "\u20A9" to "won", "\u20BF" to "bitcoin"
        )

        private val SCALE_MAP = mapOf(
            "K" to "thousand", "M" to "million", "B" to "billion", "T" to "trillion"
        )

        private val UNIT_MAP = mapOf(
            "km" to "kilometers", "kg" to "kilograms", "mg" to "milligrams",
            "ml" to "milliliters", "gb" to "gigabytes", "mb" to "megabytes",
            "kb" to "kilobytes", "tb" to "terabytes",
            "hz" to "hertz", "khz" to "kilohertz", "mhz" to "megahertz", "ghz" to "gigahertz",
            "mph" to "miles per hour", "kph" to "kilometers per hour",
            "ms" to "milliseconds", "ns" to "nanoseconds", "\u00B5s" to "microseconds",
            "\u00B0c" to "degrees Celsius", "c\u00B0" to "degrees Celsius",
            "\u00B0f" to "degrees Fahrenheit", "f\u00B0" to "degrees Fahrenheit"
        )

        private val DECADE_MAP = mapOf(
            0 to "hundreds", 1 to "tens", 2 to "twenties", 3 to "thirties", 4 to "forties",
            5 to "fifties", 6 to "sixties", 7 to "seventies", 8 to "eighties", 9 to "nineties"
        )

        // Regex patterns
        private val RE_URL = Regex("""https?://\S+|www\.\S+""")
        private val RE_EMAIL = Regex("""\b[\w.+\-]+@[\w\-]+\.[a-z]{2,}\b""", RegexOption.IGNORE_CASE)
        private val RE_HTML = Regex("""<[^>]+>""")
        private val RE_PUNCT = Regex("""[^\w\s.,?!;:\-\u2014\u2013\u2026]""")
        private val RE_SPACES = Regex("""\s+""")
        private val RE_NUMBER = Regex("""(?<![a-zA-Z])-?[\d,]+(?:\.\d+)?""")
        private val RE_ORDINAL = Regex("""\b(\d+)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
        private val RE_PERCENT = Regex("""(-?[\d,]+(?:\.\d+)?)\s*%""")
        private val RE_CURRENCY = Regex("""([$\u20AC\u00A3\u00A5\u20B9\u20A9\u20BF])\s*([\d,]+(?:\.\d+)?)\s*([KMBT])?(?![a-zA-Z\d])""")
        private val RE_TIME = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)
        private val RE_RANGE = Regex("""(?<!\w)(\d+)-(\d+)(?!\w)""")
        private val RE_MODEL_VER = Regex("""\b([a-zA-Z][a-zA-Z0-9]*)-(\d[\d.]*)(?=[^\d.]|$)""")
        private val RE_UNIT = Regex("""(\d+(?:\.\d+)?)\s*(km|kg|mg|ml|gb|mb|kb|tb|hz|khz|mhz|ghz|mph|kph|\u00B0[cCfF]|[cCfF]\u00B0|ms|ns|\u00B5s)\b""", RegexOption.IGNORE_CASE)
        private val RE_SCALE = Regex("""(?<![a-zA-Z])(\d+(?:\.\d+)?)\s*([KMBT])(?![a-zA-Z\d])""")
        private val RE_SCI = Regex("""(?<![a-zA-Z\d])(-?\d+(?:\.\d+)?)[eE]([+\-]?\d+)(?![a-zA-Z\d])""")
        private val RE_FRACTION = Regex("""\b(\d+)\s*/\s*(\d+)\b""")
        private val RE_DECADE = Regex("""\b(\d{1,3})0s\b""")
        private val RE_LEAD_DEC = Regex("""(?<!\d)\.(\d)""")
        private val RE_IP = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")

        fun threeDigitsToWords(n: Int): String {
            if (n == 0) return ""
            val parts = mutableListOf<String>()
            val hundreds = n / 100
            val remainder = n % 100
            if (hundreds > 0) parts.add("${ONES[hundreds]} hundred")
            if (remainder < 20) {
                if (remainder > 0) parts.add(ONES[remainder])
            } else {
                val tensWord = TENS[remainder / 10]
                val onesWord = ONES[remainder % 10]
                parts.add(if (onesWord.isNotEmpty()) "$tensWord-$onesWord" else tensWord)
            }
            return parts.joinToString(" ")
        }

        fun numberToWords(n: Long): String {
            if (n == 0L) return "zero"
            if (n < 0) return "negative ${numberToWords(-n)}"

            // X00-X999 read as "X hundred" (e.g. 1200 -> "twelve hundred")
            if (n in 100..9999 && n % 100 == 0L && n % 1000 != 0L) {
                val hundreds = (n / 100).toInt()
                if (hundreds < 20) return "${ONES[hundreds]} hundred"
            }

            var num = n
            val parts = mutableListOf<String>()
            for (i in SCALE.indices) {
                val chunk = (num % 1000).toInt()
                if (chunk != 0) {
                    val chunkWords = threeDigitsToWords(chunk)
                    parts.add(if (SCALE[i].isNotEmpty()) "$chunkWords ${SCALE[i]}" else chunkWords)
                }
                num /= 1000
                if (num == 0L) break
            }
            return parts.reversed().joinToString(" ")
        }

        fun floatToWords(value: String, decimalSep: String = "point"): String {
            var text = value
            val negative = text.startsWith("-")
            if (negative) text = text.substring(1)

            val result = if ("." in text) {
                val parts = text.split(".", limit = 2)
                val intWords = if (parts[0].isNotEmpty()) numberToWords(parts[0].toLong()) else "zero"
                val digitMap = arrayOf("zero") + ONES.drop(1)
                val decWords = parts[1].map { digitMap[it.digitToInt()] }.joinToString(" ")
                "$intWords $decimalSep $decWords"
            } else {
                numberToWords(text.toLong())
            }

            return if (negative) "negative $result" else result
        }
    }

    // ── Expansion helpers ──

    private fun ordinalSuffix(n: Int): String {
        val word = numberToWords(n.toLong())
        val prefix: String
        val last: String
        val joiner: String

        if ("-" in word) {
            val idx = word.lastIndexOf("-")
            prefix = word.substring(0, idx)
            last = word.substring(idx + 1)
            joiner = "-"
        } else {
            val parts = word.split(" ")
            if (parts.size >= 2) {
                prefix = parts.dropLast(1).joinToString(" ")
                last = parts.last()
                joiner = " "
            } else {
                prefix = ""
                last = parts[0]
                joiner = ""
            }
        }

        val lastOrd = ORDINAL_EXCEPTIONS[last] ?: when {
            last.endsWith("t") -> last + "h"
            last.endsWith("e") -> last.dropLast(1) + "th"
            else -> last + "th"
        }

        return if (prefix.isNotEmpty()) "$prefix$joiner$lastOrd" else lastOrd
    }

    private fun expandOrdinals(text: String): String {
        return RE_ORDINAL.replace(text) { ordinalSuffix(it.groupValues[1].toInt()) }
    }

    private fun expandPercentages(text: String): String {
        return RE_PERCENT.replace(text) { match ->
            val raw = match.groupValues[1].replace(",", "")
            if ("." in raw) {
                floatToWords(raw) + " percent"
            } else {
                numberToWords(raw.toLong()) + " percent"
            }
        }
    }

    private fun expandCurrency(text: String): String {
        return RE_CURRENCY.replace(text) { match ->
            val symbol = match.groupValues[1]
            val raw = match.groupValues[2].replace(",", "")
            val scaleSuffix = match.groupValues[3]
            val unit = CURRENCY_SYMBOLS[symbol] ?: ""

            if (scaleSuffix.isNotEmpty()) {
                val scaleWord = SCALE_MAP[scaleSuffix] ?: scaleSuffix
                val num = if ("." in raw) floatToWords(raw) else numberToWords(raw.toLong())
                "$num $scaleWord ${unit}s".trim()
            } else if ("." in raw) {
                val parts = raw.split(".", limit = 2)
                val decPart = parts[1].take(2).padEnd(2, '0')
                val decVal = decPart.toInt()
                val intWords = numberToWords(parts[0].toLong())
                var result = if (unit.isNotEmpty()) "$intWords ${unit}s" else intWords
                if (decVal > 0) {
                    val cents = numberToWords(decVal.toLong())
                    result += " and $cents cent${if (decVal != 1) "s" else ""}"
                }
                result
            } else {
                val v = raw.toLong()
                val words = numberToWords(v)
                if (unit.isNotEmpty()) {
                    "$words ${unit}${if (v != 1L) "s" else ""}"
                } else words
            }
        }
    }

    private fun expandTime(text: String): String {
        return RE_TIME.replace(text) { match ->
            val h = match.groupValues[1].toInt()
            val mins = match.groupValues[2].toInt()
            val suffix = if (match.groupValues[4].isNotEmpty()) " ${match.groupValues[4].lowercase()}" else ""
            val hWords = numberToWords(h.toLong())
            when {
                mins == 0 && match.groupValues[4].isEmpty() -> "$hWords hundred$suffix"
                mins == 0 -> "$hWords$suffix"
                mins < 10 -> "$hWords oh ${numberToWords(mins.toLong())}$suffix"
                else -> "$hWords ${numberToWords(mins.toLong())}$suffix"
            }
        }
    }

    private fun expandRanges(text: String): String {
        return RE_RANGE.replace(text) { match ->
            val lo = numberToWords(match.groupValues[1].toLong())
            val hi = numberToWords(match.groupValues[2].toLong())
            "$lo to $hi"
        }
    }

    private fun expandModelNames(text: String): String {
        return RE_MODEL_VER.replace(text) { match ->
            "${match.groupValues[1]} ${match.groupValues[2]}"
        }
    }

    private fun expandUnits(text: String): String {
        return RE_UNIT.replace(text) { match ->
            val raw = match.groupValues[1]
            val unit = match.groupValues[2].lowercase()
            val expanded = UNIT_MAP[unit] ?: match.groupValues[2]
            val num = if ("." in raw) floatToWords(raw) else numberToWords(raw.toLong())
            "$num $expanded"
        }
    }

    private fun expandScaleSuffixes(text: String): String {
        return RE_SCALE.replace(text) { match ->
            val raw = match.groupValues[1]
            val suffix = match.groupValues[2]
            val scaleWord = SCALE_MAP[suffix] ?: suffix
            val num = if ("." in raw) floatToWords(raw) else numberToWords(raw.toLong())
            "$num $scaleWord"
        }
    }

    private fun expandScientificNotation(text: String): String {
        return RE_SCI.replace(text) { match ->
            val coeffRaw = match.groupValues[1]
            val exp = match.groupValues[2].toInt()
            val coeffWords = if ("." in coeffRaw) floatToWords(coeffRaw) else numberToWords(coeffRaw.toLong())
            val expWords = numberToWords(kotlin.math.abs(exp).toLong())
            val sign = if (exp < 0) "negative " else ""
            "$coeffWords times ten to the $sign$expWords"
        }
    }

    private fun expandFractions(text: String): String {
        return RE_FRACTION.replace(text) { match ->
            val num = match.groupValues[1].toInt()
            val den = match.groupValues[2].toInt()
            if (den == 0) return@replace match.value
            val numWords = numberToWords(num.toLong())
            val denomWord = when (den) {
                2 -> if (num == 1) "half" else "halves"
                4 -> if (num == 1) "quarter" else "quarters"
                else -> {
                    val ord = ordinalSuffix(den)
                    if (num != 1) "${ord}s" else ord
                }
            }
            "$numWords $denomWord"
        }
    }

    private fun expandDecades(text: String): String {
        return RE_DECADE.replace(text) { match ->
            val base = match.groupValues[1].toInt()
            val decadeDigit = base % 10
            val decadeWord = DECADE_MAP[decadeDigit] ?: ""
            if (base < 10) decadeWord
            else {
                val centuryPart = base / 10
                "${numberToWords(centuryPart.toLong())} $decadeWord"
            }
        }
    }

    private fun expandIpAddresses(text: String): String {
        val digitNames = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        fun octet(s: String) = s.map { digitNames[it.digitToInt()] }.joinToString(" ")
        return RE_IP.replace(text) { match ->
            (1..4).joinToString(" dot ") { octet(match.groupValues[it]) }
        }
    }

    private fun expandPhoneNumbers(text: String): String {
        val digitNames = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        fun digits(s: String) = s.map { digitNames[it.digitToInt()] }.joinToString(" ")

        var t = text
        // 11-digit: 1-800-555-0199
        t = Regex("""(?<!\d-)(?<!\d)\b(\d{1,2})-(\d{3})-(\d{3})-(\d{4})\b(?!-\d)""").replace(t) { match ->
            (1..4).joinToString(" ") { digits(match.groupValues[it]) }
        }
        // 10-digit: 555-123-4567
        t = Regex("""(?<!\d-)(?<!\d)\b(\d{3})-(\d{3})-(\d{4})\b(?!-\d)""").replace(t) { match ->
            (1..3).joinToString(" ") { digits(match.groupValues[it]) }
        }
        // 7-digit: 555-1234
        t = Regex("""(?<!\d-)\b(\d{3})-(\d{4})\b(?!-\d)""").replace(t) { match ->
            (1..2).joinToString(" ") { digits(match.groupValues[it]) }
        }
        return t
    }

    private fun normalizeLeadingDecimals(text: String): String {
        var t = Regex("""(?<!\d)(-)\.(\d)""").replace(text, "$1" + "0.$2")
        t = RE_LEAD_DEC.replace(t, "0.$1")
        return t
    }

    private fun replaceNumbers(text: String): String {
        return RE_NUMBER.replace(text) { match ->
            val raw = match.value.replace(",", "")
            try {
                if ("." in raw) {
                    floatToWords(raw)
                } else {
                    numberToWords(raw.toLong())
                }
            } catch (_: NumberFormatException) {
                match.value
            }
        }
    }

    private fun expandContractions(text: String): String {
        var t = text
        val replacements = listOf(
            Regex("""\bcan't\b""", RegexOption.IGNORE_CASE) to "cannot",
            Regex("""\bwon't\b""", RegexOption.IGNORE_CASE) to "will not",
            Regex("""\bshan't\b""", RegexOption.IGNORE_CASE) to "shall not",
            Regex("""\bain't\b""", RegexOption.IGNORE_CASE) to "is not",
            Regex("""\blet's\b""", RegexOption.IGNORE_CASE) to "let us",
            Regex("""\b(\w+)n't\b""", RegexOption.IGNORE_CASE) to "$1 not",
            Regex("""\b(\w+)'re\b""", RegexOption.IGNORE_CASE) to "$1 are",
            Regex("""\b(\w+)'ve\b""", RegexOption.IGNORE_CASE) to "$1 have",
            Regex("""\b(\w+)'ll\b""", RegexOption.IGNORE_CASE) to "$1 will",
            Regex("""\b(\w+)'d\b""", RegexOption.IGNORE_CASE) to "$1 would",
            Regex("""\b(\w+)'m\b""", RegexOption.IGNORE_CASE) to "$1 am",
            Regex("""\bit's\b""", RegexOption.IGNORE_CASE) to "it is",
        )
        for ((pattern, replacement) in replacements) {
            t = pattern.replace(t, replacement)
        }
        return t
    }

    // ── Removal helpers ──

    private fun normalizeUnicode(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
    }

    private fun removeHtmlTags(text: String): String {
        return RE_HTML.replace(text, " ")
    }

    private fun removeUrls(text: String): String {
        return RE_URL.replace(text, "").trim()
    }

    private fun removeEmails(text: String): String {
        return RE_EMAIL.replace(text, "").trim()
    }

    private fun removePunctuation(text: String): String {
        return RE_PUNCT.replace(text, " ")
    }

    private fun removeExtraWhitespace(text: String): String {
        return RE_SPACES.replace(text, " ").trim()
    }
}
