package ru.dietdiary.offline

import java.util.Locale

/** Language support is bundled with the app; no network lookup or saved language override. */
object LocalePolicy {
    val languages = setOf(
        "af", "sq", "am", "ar", "hy", "az", "bn", "eu", "be", "bg", "my", "ca", "zh", "hr",
        "cs", "da", "nl", "en", "et", "fil", "fi", "fr", "gl", "ka", "de", "el", "gu", "he",
        "hi", "hu", "is", "id", "it", "ja", "kn", "kk", "km", "ko", "ky", "lo", "lv", "lt",
        "mk", "ms", "ml", "mr", "mn", "ne", "no", "fa", "pl", "pt", "pa", "ro", "rm", "ru",
        "sr", "si", "sk", "sl", "es", "sw", "sv", "ta", "te", "th", "tr", "uk", "ur", "vi",
    )

    fun resolve(requested: Locale): Locale {
        val language = when (requested.language) {
            "iw" -> "he"
            "in" -> "id"
            "tl" -> "fil"
            "nb" -> "no"
            else -> requested.language
        }
        if (language !in languages) return Locale.US
        if (language == "zh") {
            val traditional = when (requested.script) {
                "Hans" -> false
                "Hant" -> true
                else -> requested.country in setOf("TW", "HK", "MO")
            }
            return Locale.forLanguageTag(if (traditional) {
                if (requested.country in setOf("HK", "MO")) "zh-Hant-HK" else "zh-Hant-TW"
            } else "zh-Hans-CN")
        }
        return Locale.Builder().setLocale(requested).setLanguage(language).build()
    }
}

/** Accepts localized decimal digits and the familiar dot/comma notation without partial parses. */
fun parseLocalizedNumber(text: String): Double? {
    val normalized = buildString {
        text.trim().forEach { c ->
            when {
                c in "\u061c\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" -> Unit
                Character.digit(c, 10) >= 0 -> append(Character.digit(c, 10))
                c == '\u066b' -> append('.')
                c == '\u2212' -> append('-')
                else -> append(c)
            }
        }
    }
    // The UI emits no thousands grouping. Pasted grouped values must have valid groups.
    var number = normalized
    for (separator in listOf('\u066c', '\u00a0', '\u202f', ' ')) {
        if (separator in number) {
            val escaped = Regex.escape(separator.toString())
            if (!Regex("[+-]?[0-9]{1,3}(?:$escaped[0-9]{3})+(?:[.,][0-9]+)?").matches(number)) return null
            number = number.replace(separator.toString(), "")
        }
    }
    if ('.' in number && ',' in number) {
        val decimal = if (number.lastIndexOf('.') > number.lastIndexOf(',')) '.' else ','
        val group = if (decimal == '.') ',' else '.'
        val e = Regex.escape(group.toString())
        val d = Regex.escape(decimal.toString())
        if (!Regex("[+-]?[0-9]{1,3}(?:$e[0-9]{3})+$d[0-9]+").matches(number)) return null
        number = number.replace(group.toString(), "")
    }
    if (!Regex("[+-]?(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)").matches(number)) return null
    return number.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

fun parseLocalizedInteger(text: String): Int? = parseLocalizedNumber(text)?.takeIf {
    it % 1.0 == 0.0 && it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble()
}?.toInt()

/** Editable values retain every decimal digit; display rounding must not change saved data. */
fun formatLocalizedInputNumber(value: Double, locale: Locale): String {
    val symbols = java.text.DecimalFormatSymbols.getInstance(locale)
    return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().map { char ->
        when {
            char == '.' -> symbols.decimalSeparator
            char in '0'..'9' -> (symbols.zeroDigit.code + char.digitToInt()).toChar()
            else -> char
        }
    }.joinToString("")
}
