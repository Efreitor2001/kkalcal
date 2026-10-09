package ru.dietdiary.offline

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Runs without credentials and never replaces the installed application's diary or session. */
@RunWith(AndroidJUnit4::class)
class CloudOfflineUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var application: Context
    private lateinit var testRoot: File
    private lateinit var store: AppStore
    private val dark = mutableStateOf(false)

    @Before fun setUp() {
        assumeTrue("This scenario covers a build without a Google Client ID", BuildConfig.GOOGLE_CLIENT_ID.isBlank())
        application = ApplicationProvider.getApplicationContext()
        assumeTrue("Leave any existing Google session untouched", CloudSync.get(application).status.email == null)
        testRoot = File(application.cacheDir, "cloud-offline-ui-${UUID.randomUUID()}")
        val files = File(testRoot, "files").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(application) { override fun getFilesDir(): File = files }
        store = AppStore(isolated)
        assertFalse(store.isReadOnly)
        val today = LocalDate.now().toString()
        store.saveLog(DailyLog(date = today, weight = 75.0))
        val apple = Product("offline-ui-product", "Яблоко, пример", "Пример", Macros(52.0, 0.3, 0.2, 13.8))
        store.saveProduct(apple)
        store.addEntry(Entry("offline-ui-entry", today, "Перекус", apple.id, apple.name, 150.0, apple.macros))
        compose.setContent { DietDiaryTheme(darkTheme = dark.value) { DietDiaryApp(store) } }
    }

    @After fun tearDown() {
        if (::testRoot.isInitialized) {
            compose.waitForIdle()
            testRoot.deleteRecursively()
        }
    }

    @Test fun unconfiguredCloudExplainsOfflineUseAndAchievementsRemainAvailable() {
        val original = store.exportJson()
        compose.onNodeWithTag("tab_4").performClick()
        compose.onNodeWithTag("open_cloud").performScrollTo().performClick()
        compose.onNodeWithText("Облачная синхронизация").assertIsDisplayed()
        compose.onNodeWithText("Без аккаунта").assertIsDisplayed()
        capture("cloud-light.png")
        compose.runOnIdle { dark.value = true }
        capture("cloud-dark.png")

        compose.onNodeWithTag("connect_google").performScrollTo().performClick()
        compose.onNodeWithText("Подключение Google ещё не настроено для этой сборки. Дневник и резервные копии работают без аккаунта.")
            .assertIsDisplayed()
        compose.onNodeWithText("Понятно").performClick()
        assertEquals(original, store.exportJson())

        compose.onNodeWithText("‹  Назад").performScrollTo().performClick()
        compose.onNodeWithTag("open_achievements").performScrollTo().performClick()
        compose.onNodeWithTag("achievements_screen").assertIsDisplayed()
        compose.onNodeWithText("Достижения").assertIsDisplayed()
        compose.onNodeWithText("ВАШ ПРОГРЕСС").assertIsDisplayed()
        assertTrue(store.data.achievements.size >= 2)
        capture("achievements-dark.png")
        compose.runOnIdle { dark.value = false }
        capture("achievements-light.png")

        compose.onNodeWithText("‹  Назад").performClick()
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNodeWithText("Дневник диеты").assertIsDisplayed()
        assertEquals(original, store.exportJson())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(checkNotNull(application.getExternalFilesDir(null)), "screenshots").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { output ->
            assertTrue("Could not write screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
