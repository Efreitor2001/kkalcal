package ru.dietdiary.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LocalePolicyTest {
    @Test fun supportedSystemLanguageKeepsItsRegionalFormatting() {
        listOf("ru-RU", "ru-BY", "en-AU", "fr-CA", "pt-BR", "es-419", "ar-EG", "fa-IR").forEach { tag ->
            assertEquals(tag, Locale.forLanguageTag(tag), LocalePolicy.resolve(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun unsupportedLanguageAlwaysFallsBackToEnglishRegardlessOfGlobalLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            listOf("eo", "zu-ZA", "zz-ZZ", "und").forEach { tag ->
                assertEquals(tag, Locale.US, LocalePolicy.resolve(Locale.forLanguageTag(tag)))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun legacyAndroidLanguageAliasesResolveToSupportedLanguages() {
        mapOf("iw-IL" to "he", "in-ID" to "id", "tl-PH" to "fil", "nb-NO" to "no").forEach { (tag, language) ->
            assertEquals(tag, language, LocalePolicy.resolve(Locale.forLanguageTag(tag)).language)
        }
    }

    @Test fun chineseScriptTakesPriorityOverRegionAndRegionSuppliesMissingScript() {
        mapOf(
            "zh-Hans-TW" to "Hans", "zh-Hant-CN" to "Hant", "zh-HK" to "Hant",
            "zh-TW" to "Hant", "zh-MO" to "Hant", "zh-CN" to "Hans", "zh-SG" to "Hans",
        ).forEach { (tag, script) ->
            assertEquals(tag, script, LocalePolicy.resolve(Locale.forLanguageTag(tag)).script)
        }
    }

    @Test fun decimalDigitsFromSystemKeyboardsProduceTheSameQuantity() {
        listOf("123.5", "123,5", "١٢٣٫٥", "۱۲۳٫۵", "१२३.५", "১২৩.৫", "１２３.５").forEach { text ->
            assertNumber(text, 123.5)
        }
        assertNumber("\u061c−۱۲٫۵\u200f", -12.5)
        assertNumber("\u2067١٢٣٫٥\u2069", 123.5)
        assertNumber("  +0,25  ", 0.25)
        assertNumber(",5", 0.5)
        assertNumber("0", 0.0)
    }

    @Test fun pastedThousandsGroupingMustBeCompleteAndConsistent() {
        listOf("1 234 567,5", "1\u00a0234\u00a0567.5", "1\u202f234\u202f567,5", "1,234,567.5", "1.234.567,5", "١٬٢٣٤٬٥٦٧٫٥").forEach { text ->
            assertNumber(text, 1_234_567.5)
        }
        listOf("1 23", "12\u00a034", "1\u202f234 567", "١٬٢٣٫٥", "12,34.5", "1.23.456,7", "1 234 56").forEach { text ->
            assertNull(text, parseLocalizedNumber(text))
        }
    }

    @Test fun parserRejectsPartialInputAndNonFiniteValues() {
        listOf("", " ", "12kg", "12\n3", "10/2", "1..2", "1,2,3", "--2", "1-2", ".", ",", "NaN", "Infinity", "∞", "1e3", "0x10", "9".repeat(400)).forEach { text ->
            assertNull(text.take(30), parseLocalizedNumber(text))
        }
    }

    @Test fun integerParsingAcceptsLocalizedWholeValuesAndRejectsFractionsOrOverflow() {
        assertEquals(12000, parseLocalizedInteger("١٢٬٠٠٠"))
        assertEquals(48, parseLocalizedInteger("۴۸"))
        assertEquals(24, parseLocalizedInteger("२४"))
        assertEquals(0, parseLocalizedInteger("0.0"))
        assertEquals(Int.MAX_VALUE, parseLocalizedInteger(Int.MAX_VALUE.toString()))
        assertEquals(Int.MIN_VALUE, parseLocalizedInteger(Int.MIN_VALUE.toString()))
        listOf("1.5", "۰٫۱", "2147483648", "-2147483649", "42 steps", "").forEach { text ->
            assertNull(text, parseLocalizedInteger(text))
        }
    }

    private fun assertNumber(text: String, expected: Double) {
        val actual = parseLocalizedNumber(text)
        requireNotNull(actual) { "Failed to parse localized number: $text" }
        assertEquals(text, expected, actual, 0.00000001)
    }
}
