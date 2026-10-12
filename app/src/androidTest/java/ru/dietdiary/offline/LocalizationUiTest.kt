package ru.dietdiary.offline

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import java.util.UUID

/** Localized real screens and packaged resources; no credentials, production ads or user data. */
@RunWith(AndroidJUnit4::class)
class LocalizationUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var application: Context
    private lateinit var originalConfiguration: Configuration
    private lateinit var originalLocale: Locale
    private lateinit var testRoot: File
    private lateinit var store: AppStore
    private val displayContext = mutableStateOf<Context?>(null)

    @Before fun setUp() {
        assertTrue("Localization tests must use a debug build", BuildConfig.DEBUG)
        val adIds = AdPlacement.entries.map { it.unitId } + BuildConfig.YANDEX_APP_OPEN_ID
        assertTrue("Do not run localization tests with configured advertising IDs", adIds.all { it.isBlank() })
        application = ApplicationProvider.getApplicationContext()
        originalConfiguration = Configuration(application.resources.configuration)
        originalLocale = Locale.getDefault()
        testRoot = File(application.cacheDir, "localization-ui-${UUID.randomUUID()}")
        val files = File(testRoot, "files").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(application) { override fun getFilesDir(): File = files }
        store = AppStore(isolated)
        assertFalse(store.isReadOnly)
    }

    @After fun tearDown() {
        if (::application.isInitialized) {
            compose.waitForIdle()
            Localization.install(application, originalConfiguration)
            Locale.setDefault(originalLocale)
        }
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
    }

    @Test fun englishSystemShowsEnglishUiAndTranslatedBuiltInFoods() {
        render("en-US")
        compose.onNodeWithText("Diet Diary").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous day").assertIsDisplayed()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithText("Foods and dishes").assertIsDisplayed()
        val apple = store.data.products.single { it.id == "seed_9003" }
        assertNotEquals(apple.name, apple.localizedName())
        search(apple.localizedName())
        compose.onNodeWithTag("food_${apple.id}").assertIsDisplayed().assertTextContains(apple.localizedName())
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithText("My diary").assertIsDisplayed()
        compose.onNodeWithTag("tab_4").performClick()
        compose.onNodeWithText("Settings and data backup").assertIsDisplayed()
    }

    @Test fun russianSystemPreservesRussianUiAndOriginalSeedText() {
        render("ru-RU")
        compose.onNodeWithText("Дневник диеты").assertIsDisplayed()
        compose.onNodeWithContentDescription("Предыдущий день").assertIsDisplayed()
        assertEquals("9000 шагов", tr(R.string.core_steps_summary, "9000"))
        assertEquals("6 ч 48 мин", sleepLabel(6.8))
        store.data.products.forEach { product ->
            assertEquals(product.id, product.name, product.localizedName())
            assertEquals(product.id, product.category, product.localizedCategory())
            assertEquals(product.id, product.note, product.localizedNote())
        }
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithText("Продукты и блюда").assertIsDisplayed()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithText("Мой дневник").assertIsDisplayed()
    }

    @Test fun arabicSystemUsesRtlAndArabicDecimalInputReachesTheStoredPortion() {
        val context = render("ar-EG")
        assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
        val title = tr(R.string.core_app_name)
        assertTrue("Arabic app title did not load", title.any { it in '\u0600'..'\u06ff' })
        compose.onNodeWithText(title).assertIsDisplayed()
        val previous = compose.onNodeWithContentDescription(tr(R.string.core_previous_day))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription(tr(R.string.core_next_day))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Previous day must be on the right in RTL", previous.center.x > next.center.x)

        val apple = store.data.products.single { it.id == "seed_9003" }
        compose.onNodeWithTag("tab_1").performClick()
        search(apple.localizedName())
        compose.onNodeWithTag("food_${apple.id}").performClick()
        compose.onNodeWithTag("portion_grams").performScrollTo().performTextReplacement("١٢٥٫٥")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("editor_save").performScrollTo().performClick()
        compose.waitForIdle()
        val saved = store.data.entries.single()
        assertEquals(125.5, saved.grams, 0.000001)
        assertEquals("Завтрак", saved.meal)
        assertEquals(apple.name, saved.name)
        assertEquals(apple.macros, saved.per100)
        assertEquals(65.26, saved.total.kcal, 0.000001)

        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("add_log").performClick()
        compose.onNodeWithTag("log_steps").performScrollTo().performTextReplacement("١٢٬٠٠٠")
        compose.onNodeWithTag("log_sleep_hours").performScrollTo().performTextReplacement("٦")
        compose.onNodeWithTag("log_sleep_minutes").performScrollTo().performTextReplacement("٤٨")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("editor_save").performScrollTo().performClick()
        compose.waitForIdle()
        val daily = store.data.logs.single()
        assertEquals(12000, daily.steps)
        assertEquals(6.8, checkNotNull(daily.sleep), 0.000001)
    }

    @Test fun unsupportedPrimarySystemLanguageFallsBackToEnglishEvenWithRussianSecond() {
        val requested = Configuration(application.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("eo"), Locale.forLanguageTag("ru-RU")))
        }
        render(requested)
        assertEquals(Locale.US, appLocale())
        compose.onNodeWithText("Diet Diary").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous day").assertIsDisplayed()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithText("Foods and dishes").assertIsDisplayed()
    }

    @Test fun changingLanguageDuringAnUnchangedProductEditKeepsAllOriginalStoredFields() {
        render("en-US")
        val original = store.data.products.single { it.id == "seed_9003" }
        val displayedName = original.localizedName()
        compose.onNodeWithTag("tab_1").performClick()
        search(displayedName)
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("product_name").assertTextContains(displayedName)
        compose.runOnIdle { installLocale(configuration("ru-RU")) }
        // Recreate the composition with the same saved editor state, as on configuration change.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("product_name").assertTextContains(displayedName)
        compose.onNodeWithTag("editor_save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(original, store.data.products.single { it.id == original.id })
        assertEquals(original, DataJson.decode(store.exportJson()).products.single { it.id == original.id })
    }

    @Test fun all86PlayLocalesResolveEveryPackagedAppStringAndKeepFormattingArguments() {
        val tags = supportedLocaleTags()
        assertEquals("Google Play locale list unexpectedly changed", 86, tags.size)
        assertEquals(tags.size, tags.toSet().size)
        val fields = R.string::class.java.fields.filter {
            it.name == "app_name" || it.name.startsWith("core_") || it.name.startsWith("data_") || it.name.startsWith("secondary_")
        }
        assertTrue("App string inventory is incomplete", fields.size >= 668)
        val english = application.createConfigurationContext(configuration("en-US")).resources
        val formatting = Regex("%[0-9]+\\\$[sd]")
        val mustBeTranslated = listOf(R.string.core_optional_diary_help, R.string.core_restore_warning, R.string.core_food_state_hint)
        tags.forEach { tag ->
            val resources = application.createConfigurationContext(Localization.configuration(configuration(tag))).resources
            fields.forEach { field ->
                val id = field.getInt(null)
                val text = resources.getString(id)
                assertTrue("Empty $tag/${field.name}", text.isNotBlank())
                assertEquals("Changed arguments in $tag/${field.name}",
                    formatting.findAll(english.getString(id)).map { it.value }.sorted().toList(),
                    formatting.findAll(text).map { it.value }.sorted().toList())
            }
            if (Locale.forLanguageTag(tag).language != "en") {
                mustBeTranslated.forEach { id ->
                    assertNotEquals("English fallback in supported locale $tag/${resources.getResourceEntryName(id)}", english.getString(id), resources.getString(id))
                }
            }
        }
    }

    private fun configuration(tag: String) = Configuration(application.resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(tag)))
    }

    private fun installLocale(requested: Configuration): Context {
        Localization.install(application, requested)
        val context = application.createConfigurationContext(Localization.configuration(requested))
        displayContext.value = context
        return context
    }

    private fun render(tag: String): Context = render(configuration(tag))

    private fun render(requested: Configuration): Context {
        val context = installLocale(requested)
        restoration.setContent {
            val activityResults=checkNotNull(LocalActivityResultRegistryOwner.current)
            val localized = checkNotNull(displayContext.value)
            val config = localized.resources.configuration
            val direction = if (config.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config, LocalLayoutDirection provides direction, LocalActivityResultRegistryOwner provides activityResults) {
                DietDiaryTheme { DietDiaryApp(store) }
            }
        }
        return context
    }

    private fun search(text: String) {
        compose.onNodeWithTag("product_search").performTextReplacement(text)
        Espresso.closeSoftKeyboard()
    }

    private fun supportedLocaleTags(): List<String> {
        val tags = mutableListOf<String>()
        application.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    tags += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                }
                parser.next()
            }
        }
        return tags
    }
}
