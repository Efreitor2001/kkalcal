package ru.dietdiary.offline

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Store listing captures are real application composables with an isolated, synthetic diary. */
@RunWith(AndroidJUnit4::class)
class StoreListingCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var application: Context
    private lateinit var fixtureRoot: File
    private lateinit var store: AppStore
    private val dark = mutableStateOf(false)
    private val profile get() = InstrumentationRegistry.getArguments().getString("captureProfile", "phone")

    @Before fun prepareFixture() {
        application = ApplicationProvider.getApplicationContext()
        fixtureRoot = File(application.cacheDir, "store-listing-${UUID.randomUUID()}")
        val files = File(fixtureRoot, "files").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(application) { override fun getFilesDir() = files }
        store = AppStore(isolated)
        assertFalse(store.isReadOnly)
        val today = LocalDate.now()
        store.saveGoals(Goals(Macros(2200.0, 120.0, 75.0, 270.0), 80.0))
        // A stable weight and ordinary daily variation; these are illustrations, never user data.
        (34 downTo 0).forEach { daysAgo ->
            store.saveLog(DailyLog(
                date = today.minusDays(daysAgo.toLong()).toString(),
                weight = 80.0 + daysAgo / 100.0 + listOf(0.0, .15, -.05, .1, -.1)[daysAgo % 5],
                waist = 88.0, steps = 7100 + (daysAgo * 193 % 3400),
                sleep = 7.0 + (daysAgo % 5) * .25,
                calories = if (daysAgo > 0) 2010.0 + (daysAgo * 67 % 300) else null,
                protein = if (daysAgo > 0) 110.0 + (daysAgo % 5) * 4 else null,
                training = if (daysAgo == 0) "Прогулка 40 минут" else "",
                note = if (daysAgo == 0) "Хорошее самочувствие" else "",
            ))
        }
        val favorites = setOf("seed_9003", "seed_9040", "seed_1256", "seed_20010", "seed_5064")
        store.data.products.filter { it.id in favorites }.forEach { store.saveProduct(it.copy(favorite = true)) }
        val oatmeal = Product("listing-oatmeal", "Овсяная каша с яблоком", "Мои блюда",
            Macros(105.0, 3.5, 2.7, 17.0), "Овсяные хлопья, молоко, яблоко. Выход готового блюда: 450 г.", favorite = true)
        store.saveProduct(oatmeal)
        fun food(id: String, grams: Double, meal: String, day: LocalDate = today, suffix: String = "") {
            val product = store.data.products.single { it.id == id }
            store.addEntry(Entry("listing-${day}-${id}-$suffix", day.toString(), meal,
                product.id, product.name, grams, product.macros))
        }
        (1..7).forEach { food(oatmeal.id, 250.0, "Завтрак", today.minusDays(it.toLong())) }
        food(oatmeal.id, 300.0, "Завтрак")
        food("seed_9040", 120.0, "Завтрак")
        food("seed_1256", 170.0, "Перекус")
        food("seed_5064", 160.0, "Обед")
        food("seed_20010", 220.0, "Обед")
        food("seed_11529", 180.0, "Обед")
        food("seed_4053", 12.0, "Обед")
        food("seed_15237", 150.0, "Ужин")
        food("seed_20445", 180.0, "Ужин")
        food("seed_11205", 180.0, "Ужин")
        food("seed_9003", 170.0, "Перекус")
        food("seed_12061", 20.0, "Перекус")
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
        }
        compose.setContent { DietDiaryTheme(darkTheme = dark.value) { DietDiaryApp(store) } }
    }

    @After fun removeFixture() {
        compose.waitForIdle()
        fixtureRoot.deleteRecursively()
    }

    @Test fun captureStoreListing() {
        compose.onNodeWithText("Дневник диеты").assertIsDisplayed()
        capture("01-food-diary.png")

        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithText("Продукты и блюда").assertIsDisplayed()
        capture("02-food-catalog.png")

        if (profile == "phone") {
            compose.onNodeWithText("По рецепту").performClick()
            compose.onNodeWithText("Название блюда").performTextReplacement("Овсяная каша с яблоком")
            Espresso.closeSoftKeyboard()
            addIngredient("Овсяные хлопья сухие", "80")
            addIngredient("Молоко 2%", "250")
            addIngredient("Яблоко с кожурой", "120")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Вес готового блюда, г"))
            compose.onNodeWithText("Вес готового блюда, г").performTextReplacement("450")
            Espresso.closeSoftKeyboard()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Сохранить в «Мои блюда»"))
            compose.onNodeWithText("На 100 г готового блюда").assertIsDisplayed()
            capture("03-recipe-calculation.png")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Сохранить в «Мои блюда»"))
            compose.onNodeWithText("Сохранить в «Мои блюда»").performClick()
            compose.runOnIdle { assertEquals(2, store.data.products.count { it.name == "Овсяная каша с яблоком" }) }

            compose.onNodeWithTag("tab_2").performClick()
            compose.onNodeWithText("Мой дневник").assertIsDisplayed()
            capture("04-measurements.png")
        }

        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithText("Динамика").assertIsDisplayed()
        compose.onNodeWithText("Месяц").performClick()
        capture("05-weight-chart.png")

        if (profile == "phone") {
            compose.onNodeWithText("Вес · кг  ▾").performClick()
            compose.onNodeWithText("Калории · ккал").performClick()
            capture("06-calorie-chart.png")
        }

        compose.onNodeWithTag("tab_4").performClick()
        compose.onNodeWithTag("open_achievements").performScrollTo().performClick()
        compose.onNodeWithText("Начало положено").assertIsDisplayed()
        capture("07-achievements.png")

        if (profile == "phone") {
            compose.onNodeWithTag("tab_0").performClick()
            compose.runOnIdle { dark.value = true }
            compose.onNodeWithText("Дневник диеты").assertIsDisplayed()
            capture("08-dark-theme.png")
        }
        val beforePrivacy = store.exportSyncJson()
        compose.onNodeWithTag("tab_4").performClick()
        compose.onNodeWithTag("open_privacy").performScrollTo().performClick()
        compose.onNodeWithTag("privacy_screen").assertIsDisplayed()
        compose.onNodeWithText("Конфиденциальность").assertIsDisplayed()
        compose.onNodeWithText("‹  Назад").performClick()
        compose.onNodeWithTag("open_privacy").performScrollTo().assertIsDisplayed()
        assertEquals(beforePrivacy, store.exportSyncJson())
    }

    private fun addIngredient(name: String, grams: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("+ Добавить ингредиент"))
        compose.onNodeWithText("+ Добавить ингредиент").performClick()
        compose.onNodeWithText("Поиск продуктов").performTextReplacement(name)
        Espresso.closeSoftKeyboard()
        compose.onNode(hasText(name) and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Граммы").performTextReplacement(grams)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Добавить").performClick()
    }

    private fun capture(name: String) {
        Espresso.closeSoftKeyboard()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Блюдо сохранено в продуктах").fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { compose.activity.window.insetsController?.hide(WindowInsets.Type.systemBars()) }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        bitmap.setHasAlpha(false) // Encode the unchanged, opaque screen as an RGB PNG for Play.
        if (profile == "phone") {
            assertEquals("Use adb wm size 1080x1920 for phone captures", 1080, bitmap.width)
            assertEquals("Capture must include the entire real fullscreen application", 1920, bitmap.height)
        }
        val destination = File(checkNotNull(application.getExternalFilesDir(null)), "store-listing/$profile").apply { mkdirs() }
        File(destination, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
