package ru.dietdiary.offline

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppStoreTest {
    private lateinit var testRoot: File
    private lateinit var filesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        testRoot = File(application.cacheDir, "store-test-${UUID.randomUUID()}")
        filesDir = File(testRoot, "files").apply { mkdirs() }
        context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = this@AppStoreTest.filesDir
        }
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun catalogAndFullJournalSurviveReopening() {
        val store = AppStore(context)
        assertFalse(store.isReadOnly)
        assertTrue(store.data.products.size >= 100)
        store.saveProduct(product())
        store.addEntry(entry())
        store.saveLog(DailyLog("2026-10-08", weight = 81.2, waist = 92.0, steps = 7_123, sleep = 7.5,
            note = "После сна", bodyFat = 22.1, training = "Прогулка", calories = 2_145.0, protein = 120.5))
        store.saveGoals(Goals(Macros(2_000.0, 120.0, 70.0, 240.0), weight = 75.0))

        val reopened = AppStore(context)
        assertEquals(store.data, reopened.data)
        assertEquals(250.0, reopened.data.entries.single().total.kcal, 0.00001)
        assertEquals(25.0, reopened.data.entries.single().total.protein, 0.00001)
        assertEquals(12.5, reopened.data.entries.single().total.fat, 0.00001)
        assertEquals(50.0, reopened.data.entries.single().total.carbs, 0.00001)
        assertEquals(store.data, DataJson.decode(store.exportJson()))
    }

    @Test
    fun catalogChangesDoNotRewriteFoodHistory() {
        val store = AppStore(context)
        store.saveProduct(product())
        store.addEntry(entry())
        store.saveProduct(product().copy(name = "Новое название", macros = Macros(900.0, 0.0, 100.0, 0.0)))
        assertEquals(entry(), store.data.entries.single())
        store.deleteProduct(product().id)
        assertEquals(entry(), store.data.entries.single())
        assertTrue(store.data.products.none { it.id == product().id })
        assertEquals(250.0, AppStore(context).data.entries.single().total.kcal, 0.00001)
    }

    @Test
    fun invalidImportsNeverPartiallyReplaceStateOrFile() {
        val store = AppStore(context)
        store.saveProduct(product())
        store.addEntry(entry())
        store.saveLog(DailyLog("2026-10-08", steps = 0))
        val before = store.data
        val bytes = File(filesDir, AppStore.FILE_NAME).readBytes()
        val valid = store.exportJson()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) },
            { it.getJSONArray("products").put(it.getJSONArray("products").getJSONObject(0)) },
            { it.getJSONArray("entries").put(it.getJSONArray("entries").getJSONObject(0)) },
            { it.getJSONArray("logs").put(it.getJSONArray("logs").getJSONObject(0)) },
            { it.getJSONArray("entries").getJSONObject(0).put("date", "2026-02-30") },
            { it.getJSONArray("entries").getJSONObject(0).put("grams", -1) },
            { it.getJSONArray("entries").getJSONObject(0).put("grams", "250") },
            { it.getJSONArray("entries").getJSONObject(0).getJSONObject("per100").put("fat", 101) },
            { it.getJSONArray("logs").getJSONObject(0).put("steps", 10.5) },
            { it.getJSONArray("logs").getJSONObject(0).put("sleep", 25) },
            { it.getJSONArray("logs").getJSONObject(0).put("waist", 10) },
            { it.getJSONArray("logs").getJSONObject(0).put("bodyFat", 101) },
            { it.getJSONArray("logs").getJSONObject(0).put("training", "а".repeat(2_001)) },
            { it.getJSONArray("logs").getJSONObject(0).put("calories", 100_001) },
            { it.getJSONArray("logs").getJSONObject(0).put("protein", 10_001) },
            { it.getJSONObject("goals").getJSONObject("macros").put("kcal", 10001) },
            { it.getJSONObject("goals").put("weight", 0) },
        )
        mutations.forEach { mutate ->
            val json = JSONObject(valid).also(mutate).toString()
            expectIllegalArgument { store.importJson(json) }
            assertEquals(before, store.data)
            assertTrue(bytes.contentEquals(File(filesDir, AppStore.FILE_NAME).readBytes()))
        }
        expectIllegalArgument { store.importJson("{broken") }
        assertEquals(before, AppStore(context).data)
    }

    @Test
    fun optionalBodyIndicatorsAreUpsertedByDate() {
        val store = AppStore(context)
        store.saveLog(DailyLog("2026-10-08", weight = 81.0))
        store.saveLog(DailyLog("2026-10-08", waist = 91.5, steps = 0))
        assertEquals(listOf(DailyLog("2026-10-08", waist = 91.5, steps = 0)), store.data.logs)
        store.saveLog(DailyLog("2026-10-07", note = "День отдыха"))
        assertEquals(listOf("2026-10-07", "2026-10-08"), store.data.logs.map { it.date })
        val before = store.data
        expectIllegalArgument { store.saveLog(DailyLog("2026-10-09")) }
        expectIllegalArgument { store.saveLog(DailyLog("2026-10-09", weight = Double.NaN)) }
        expectIllegalArgument { store.saveLog(DailyLog("2026-10-09", sleep = Double.POSITIVE_INFINITY)) }
        assertEquals(before, store.data)
        store.deleteLog("2026-10-08")
        assertEquals("2026-10-07", store.data.logs.single().date)
    }

    @Test
    fun corruptedFileRemainsUntouchedUntilExplicitValidImport() {
        val original = AppStore(context)
        original.saveProduct(product())
        original.addEntry(entry())
        val backup = original.exportJson()
        val savedFile = File(filesDir, AppStore.FILE_NAME)
        savedFile.writeText("{broken original file")

        val damaged = AppStore(context)
        assertTrue(damaged.isReadOnly)
        assertNotNull(damaged.loadError)
        val fallback = damaged.data
        expectIoException { damaged.saveProduct(product()) }
        expectIoException { damaged.exportJson() }
        expectIllegalArgument { damaged.importJson("{broken backup") }
        assertEquals(fallback, damaged.data)
        assertEquals("{broken original file", savedFile.readText())

        damaged.importJson(backup)
        assertFalse(damaged.isReadOnly)
        assertEquals(null, damaged.loadError)
        assertEquals(original.data, damaged.data)
        assertEquals(original.data, AppStore(context).data)
    }

    @Test
    fun manualDailyTotalsAndTrainingDoNotRequireBodyMeasurements() {
        val store = AppStore(context)
        val logs = listOf(
            DailyLog("2026-10-01", calories = 0.0),
            DailyLog("2026-10-02", protein = 100.0),
            DailyLog("2026-10-03", bodyFat = 19.5),
            DailyLog("2026-10-04", training = "Бег 30 минут"),
        )
        logs.forEach(store::saveLog)
        assertEquals(logs, AppStore(context).data.logs)
        assertEquals(logs, DataJson.decode(store.exportJson()).logs)
        val before = store.data
        expectIllegalArgument { store.saveLog(DailyLog("2026-10-05", calories = Double.NaN)) }
        expectIllegalArgument { store.saveLog(DailyLog("2026-10-05", protein = -1.0)) }
        expectIllegalArgument { store.saveLog(DailyLog("2026-10-05", bodyFat = Double.POSITIVE_INFINITY)) }
        assertEquals(before, store.data)
    }

    @Test
    fun diskWriteFailureCannotPublishUnsavedState() {
        val store = AppStore(context)
        store.saveProduct(product())
        val before = store.data
        val preserved = File(testRoot, "preserved")
        assertTrue(filesDir.renameTo(preserved))
        filesDir.writeText("A file now occupies the expected data directory")

        expectIoException { store.addEntry(entry()) }
        assertEquals(before, store.data)
        assertEquals(before, DataJson.decode(File(preserved, AppStore.FILE_NAME).readText()))
    }

    private fun product() = Product("test_food", "Тестовый продукт", "Тесты", Macros(100.0, 10.0, 5.0, 20.0))
    private fun entry() = Entry("test_entry", "2026-10-08", "Обед", "test_food", "Тестовый продукт", 250.0, product().macros)

    private fun expectIllegalArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {
            // Expected: validation rejected the complete candidate before any write.
        }
    }

    private fun expectIoException(action: () -> Unit) {
        try {
            action()
            fail("Expected disk/write-protection failure")
        } catch (_: IOException) {
            // Expected: disk and read-only failures are surfaced to the UI.
        }
    }
}
