package ru.dietdiary.offline

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Exercises Android's real locale preference and Activity attachment, not an injected UI context. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class LocaleLifecycleTest {
    @Test fun platformLocaleOrderControlsFallbackAndDirectionAfterActivityRecreation() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue((AdPlacement.entries.map { it.unitId } + BuildConfig.YANDEX_APP_OPEN_ID).all { it.isBlank() })
        val app = ApplicationProvider.getApplicationContext<Context>()
        val manager = app.getSystemService(LocaleManager::class.java)
        val original = manager.applicationLocales
        try {
            val cases = listOf(
                Triple("eo,ru-RU", "en", "Diet Diary"),
                Triple("ru-RU,en-US", "ru", "Дневник диеты"),
                Triple("ar,en-US", "ar", app.createConfigurationContext(Localization.configuration(
                    android.content.res.Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("ar")) }
                )).getString(R.string.core_app_name)),
                Triple("he-IL,en-US", Locale.forLanguageTag("he").language, "יומן תזונה"),
                Triple("id-ID,en-US", Locale.forLanguageTag("id").language, "Catatan Diet"),
            )
            for ((requested, expectedLanguage, expectedTitle) in cases) {
                manager.applicationLocales = LocaleList.forLanguageTags(requested)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        assertEquals(requested, expectedLanguage, appLocale().language)
                        assertEquals(requested, expectedTitle, tr(R.string.core_app_name))
                        assertEquals(requested, expectedLanguage, activity.resources.configuration.locales[0].language)
                        assertEquals(requested, if (expectedLanguage in setOf("ar", "he", "iw")) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
                            activity.resources.configuration.layoutDirection)
                    }
                    scenario.recreate()
                    scenario.onActivity {
                        assertEquals("After recreation: $requested", expectedLanguage, appLocale().language)
                        assertEquals(expectedTitle, tr(R.string.core_app_name))
                    }
                }
            }
        } finally {
            manager.applicationLocales = original
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Localization.install(app)
            Locale.setDefault(appLocale())
        }
    }
}
