package ru.balance.offline

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Exercises the actual editor -> store -> disk path. Each test has its own files directory,
 * while assets come from the installed application. These tests cannot overwrite a user's diary.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var testRoot: File
    private lateinit var context: Context
    private lateinit var store: AppStore

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        testRoot = File(application.cacheDir, "ui-flow-${UUID.randomUUID()}")
        val testFiles = File(testRoot, "files").apply { check(mkdirs()) }
        context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = testFiles
        }
        store = AppStore(context)
        assertFalse(store.isReadOnly)
        assertTrue(store.data.products.size >= 100)
        compose.setContent { BalanceTheme { BalanceApp(store) } }
    }

    @After
    fun tearDown() {
        compose.waitForIdle()
        testRoot.deleteRecursively()
    }

    @Test
    fun foodPortionUsesDecimalGramsAndKeepsItsSnapshotAfterCatalogEdit() {
        val name = "Проверка порции"
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("new_product").performClick()
        replace("product_name", name)
        replace("product_kcal", "200")
        replace("product_protein", "10")
        replace("product_fat", "8")
        replace("product_carbs", "22")
        saveEditor()

        val product = persisted().products.single { it.name == name }
        assertEquals(Macros(200.0, 10.0, 8.0, 22.0), product.macros)

        // Select through the day screen's real food picker, including the search filter.
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("+  Добавить еду"))
        compose.onNodeWithTag("add_food").performClick()
        replace("product_search", name, scroll = false)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("food_${product.id}").performClick()
        replace("portion_grams", "0")
        compose.onNodeWithTag("editor_save").performScrollTo().assertIsNotEnabled()
        assertTrue(persisted().entries.isEmpty())

        // 125.5 g at 200 kcal/100 g must show 251 kcal before it is committed.
        replace("portion_grams", "125,5")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("251").performScrollTo().assertIsDisplayed()
        saveEditor()
        val original = persisted().entries.single()
        assertEquals(LocalDate.now().toString(), original.date)
        assertEquals(product.id, original.productId)
        assertEquals(125.5, original.grams, EPSILON)
        assertMacros(original.total, 251.0, 12.55, 10.04, 27.61)

        compose.onNodeWithTag("tab_1").performClick()
        replace("product_search", name, scroll = false)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Править").performClick()
        replace("product_kcal", "400")
        replace("product_protein", "20")
        replace("product_fat", "16")
        replace("product_carbs", "44")
        saveEditor()
        val afterCatalogEdit = persisted()
        assertEquals(Macros(400.0, 20.0, 16.0, 44.0),
            afterCatalogEdit.products.single { it.id == product.id }.macros)
        assertEquals(original, afterCatalogEdit.entries.single())

        compose.onNodeWithTag("tab_0").performClick()
        scrollDayToFood(name)
        compose.onNodeWithText("125,5 г · 251 ккал").assertIsDisplayed()
        compose.onNodeWithText(name).performClick()
        replace("portion_grams", "75,25")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("150,5").performScrollTo().assertIsDisplayed()
        saveEditor()
        val edited = persisted().entries.single()
        assertEquals(original.id, edited.id)
        assertEquals(original.per100, edited.per100)
        assertEquals(75.25, edited.grams, EPSILON)
        assertMacros(edited.total, 150.5, 7.525, 6.02, 16.555)

        scrollDayToFood(name)
        compose.onNodeWithText(name).performClick()
        // Opening an editor and saving without changes must preserve hundredths of a gram.
        compose.onNodeWithTag("portion_grams").assertTextContains("75,25")
        saveEditor()
        assertEquals(edited, persisted().entries.single())
        scrollDayToFood(name)
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithText("Удалить запись").performScrollTo().performClick()
        compose.onNodeWithText("Удалить", substring = false).performClick()
        val afterDelete = persisted()
        assertTrue(afterDelete.entries.isEmpty())
        assertTrue(afterDelete.products.any { it.id == product.id })
    }

    @Test
    fun savingUnchangedSeedProductPreservesItsFullNutritionPrecision() {
        val original = store.data.products.single { it.id == "seed_9003" }
        assertEquals(0.26, original.macros.protein, EPSILON)
        assertEquals(0.17, original.macros.fat, EPSILON)
        compose.onNodeWithTag("tab_1").performClick()
        replace("product_search", original.name, scroll = false)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Править").performClick()
        saveEditor()
        assertEquals(original, persisted().products.single { it.id == original.id })
    }

    @Test
    fun optionalDiaryFieldsAndSleepMinutesSurviveEditingWithoutPartialInvalidWrites() {
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("add_log").performClick()
        replace("log_steps", "9000")
        saveEditor()
        val stepsOnly = persisted().logs.single()
        assertEquals(9000, stepsOnly.steps)
        assertNull(stepsOnly.weight)
        assertNull(stepsOnly.waist)
        assertNull(stepsOnly.sleep)
        compose.onNodeWithText("9000 шагов").assertIsDisplayed().performClick()

        replace("log_sleep_hours", "6")
        replace("log_sleep_minutes", "48")
        saveEditor()
        val withSleep = persisted().logs.single()
        assertEquals(stepsOnly.date, withSleep.date)
        assertEquals(9000, withSleep.steps)
        assertEquals(6.8, withSleep.sleep!!, EPSILON)
        assertNull(withSleep.weight)
        val summary = "9000 шагов · Сон 6 ч 48 мин"
        compose.onNodeWithText(summary).assertIsDisplayed().performClick()
        compose.onNodeWithTag("log_sleep_hours").assertTextContains("6")
        compose.onNodeWithTag("log_sleep_minutes").assertTextContains("48")

        // A valid weight accompanying an invalid minute value must not be partially saved.
        replace("log_weight", "82,4")
        replace("log_sleep_minutes", "60")
        saveEditor()
        compose.onNodeWithText("Сон: 0–24 часа, минуты 0–59").performScrollTo().assertIsDisplayed()
        assertEquals(withSleep, persisted().logs.single())
        replace("log_sleep_minutes", "48")
        saveEditor()
        val corrected = persisted().logs.single()
        assertEquals(82.4, corrected.weight!!, EPSILON)
        assertEquals(6.8, corrected.sleep!!, EPSILON)
        assertEquals(9000, corrected.steps)

        compose.onNodeWithText(summary).performClick()
        compose.onNodeWithText("Удалить день из дневника").performScrollTo().performClick()
        compose.onNodeWithText("Удалить", substring = false).performClick()
        assertTrue(persisted().logs.isEmpty())
    }

    private fun replace(tag: String, value: String, scroll: Boolean = true) {
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.performTextReplacement(value)
    }

    private fun saveEditor() {
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("editor_save").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun scrollDayToFood(name: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
    }

    /** Reconstructing AppStore checks actual committed bytes, not only the in-memory UI state. */
    private fun persisted(): AppData {
        compose.waitForIdle()
        return AppStore(context).also { assertFalse(it.isReadOnly) }.data
    }

    private fun assertMacros(actual: Macros, kcal: Double, protein: Double, fat: Double, carbs: Double) {
        assertEquals(kcal, actual.kcal, EPSILON)
        assertEquals(protein, actual.protein, EPSILON)
        assertEquals(fat, actual.fat, EPSILON)
        assertEquals(carbs, actual.carbs, EPSILON)
    }

    companion object {
        private const val EPSILON = 0.000001
    }
}
