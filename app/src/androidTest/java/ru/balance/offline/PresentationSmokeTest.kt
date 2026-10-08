package ru.balance.offline

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Screenshots contain only synthetic fixtures; the installed application's diary is untouched. */
@RunWith(AndroidJUnit4::class)
class PresentationSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var testRoot: File
    private lateinit var application: Context
    private lateinit var store: AppStore
    private val dark = mutableStateOf(false)
    private val compact = mutableStateOf(false)

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        testRoot = File(application.cacheDir, "presentation-${UUID.randomUUID()}")
        val testFiles = File(testRoot, "files").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(application) { override fun getFilesDir(): File = testFiles }
        store = AppStore(isolated)
        assertFalse(store.isReadOnly)
        val today = LocalDate.now()
        val weights = listOf(84.2, 84.0, 83.8, 83.9, 83.5, 83.3, 83.1)
        weights.forEachIndexed { index, weight ->
            store.saveLog(DailyLog(
                date = today.minusDays((6 - index).toLong()).toString(),
                weight = weight,
                waist = 88.0 - index * 0.2,
                steps = 6100 + index * 430,
                sleep = 7.0 + (index % 3) * 0.25,
                bodyFat = 24.0 - index * 0.1,
                training = if (index == 6) "Прогулка 30 минут" else "",
                note = if (index == 6) "Пример записи" else ""
            ))
        }
        val porridge = Product("smoke-porridge", "Овсяная каша", "Пример", Macros(105.0, 3.2, 4.1, 14.2))
        val apple = Product("smoke-apple", "Яблоко", "Пример", Macros(52.0, 0.3, 0.2, 13.8))
        store.saveProduct(porridge)
        store.saveProduct(apple)
        store.addEntry(Entry("smoke-breakfast", today.toString(), "Завтрак", porridge.id, porridge.name, 250.0, porridge.macros))
        store.addEntry(Entry("smoke-snack", today.toString(), "Перекус", apple.id, apple.name, 180.0, apple.macros))
        store.saveGoals(Goals(Macros(2200.0, 110.0, 70.0, 270.0), weight = 80.0))

        compose.setContent {
            val nativeDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(nativeDensity.density, if (compact.value) 1.5f else nativeDensity.fontScale)) {
                Box(Modifier.fillMaxSize()) {
                    Box((if (compact.value) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()).testTag("presentation_surface")) {
                        BalanceTheme(darkTheme = dark.value) { BalanceApp(store) }
                    }
                }
            }
        }
    }

    @After fun tearDown() {
        compose.waitForIdle()
        testRoot.deleteRecursively()
    }

    @Test fun capturesSyntheticDiaryAndChartsInBothPalettes() {
        compose.onNodeWithText("Баланс").assertIsDisplayed()
        capture("home-light.png")

        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithText("Динамика").assertIsDisplayed()
        compose.onNodeWithText("83,1 кг").assertIsDisplayed()
        compose.onNodeWithText("Цель 80,0 кг").assertIsDisplayed()
        capture("stats-light.png")

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Изменение: −0,2 кг"))
        compose.onNodeWithText("Сегодня: 83,1 кг").assertIsDisplayed()
        compose.onNodeWithText("Вчера: 83,3 кг").assertIsDisplayed()
        compose.onNodeWithText("Изменение: −0,2 кг").assertIsDisplayed()
        capture("stats-comparison-light.png")

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithText("Динамика").assertIsDisplayed()
        capture("stats-dark.png")

        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithText("Мой дневник").assertIsDisplayed()
        capture("diary-dark.png")
        compose.runOnIdle { dark.value = false }
        capture("diary-light.png")

        compose.onNodeWithTag("tab_3").performClick()
        compose.runOnIdle { compact.value = true }
        compose.onNodeWithText("Динамика").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Вес за период"))
        capture("stats-320dp-large-font.png", innerSurface = true)
    }

    private fun capture(name: String, innerSurface: Boolean = false) {
        compose.waitForIdle()
        val outputDirectory = File(checkNotNull(application.getExternalFilesDir(null)), "screenshots").apply { mkdirs() }
        val bitmap = (if (innerSurface) compose.onNodeWithTag("presentation_surface") else compose.onRoot())
            .captureToImage().asAndroidBitmap()
        File(outputDirectory, name).outputStream().use { output ->
            assertTrue("Could not write screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
