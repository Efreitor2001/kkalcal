package ru.dietdiary.offline

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class SyncStoreTest {
    private lateinit var root: File
    private lateinit var application: Context
    private val day = "2020-01-01"
    private fun food() = Product("test_food", "Каша", "Тесты", Macros(100.0, 10.0, 5.0, 20.0))
    private fun entry(id: String) = Entry(id, day, "Обед", "test_food", "Каша", 250.0, food().macros)

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        root = File(application.cacheDir, "sync-test-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun tearDown() { root.deleteRecursively() }
    private fun context(name: String): Context = object : ContextWrapper(application) {
        private val directory = File(root, name).apply { mkdirs() }
        override fun getFilesDir(): File = directory
    }
    private fun exchange(a: AppStore, b: AppStore) {
        val aa = a.exportSyncJson(); val bb = b.exportSyncJson()
        a.mergeSyncJson(bb); b.mergeSyncJson(aa)
        a.mergeSyncJson(b.exportSyncJson()); b.mergeSyncJson(a.exportSyncJson())
    }

    @Test fun independentRecordsFieldsAndGoalsSurviveConcurrentOfflineEdits() {
        val a = AppStore(context("a")) { 1000L }
        val b = AppStore(context("b")) { 1000L }
        assertEquals(a.exportSyncJson(), b.exportSyncJson()) // Seed IDs never become new edits.
        a.saveLog(DailyLog(day, weight = 80.0))
        b.saveLog(DailyLog(day, waist = 91.0, steps = 8000))
        a.addEntry(entry("a_food")); b.addEntry(entry("b_food"))
        a.saveGoals(Goals(Macros(kcal = 2000.0)))
        b.saveGoals(Goals(Macros(protein = 120.0), weight = 75.0))
        exchange(a, b)
        assertEquals(a.exportSyncJson(), b.exportSyncJson())
        assertEquals(setOf("a_food", "b_food"), a.data.entries.map { it.id }.toSet())
        assertEquals(DailyLog(day, weight = 80.0, waist = 91.0, steps = 8000), a.data.logs.single())
        assertEquals(2000.0, a.data.goals.macros.kcal, 0.0)
        assertEquals(120.0, a.data.goals.macros.protein, 0.0)
        assertEquals(75.0, a.data.goals.weight!!, 0.0)
        assertTrue(a.data.achievements.containsKey("first_weight"))
        assertTrue(a.data.achievements.containsKey("first_food"))
    }

    @Test fun firstConnectionOfFreshCatalogCannotOverwriteEditedSeedProduct() {
        val a = AppStore(context("edited")) { 100L }
        val fresh = AppStore(context("fresh")) { 4_000_000_000_000L }
        val seed = a.data.products.first()
        val corrected = seed.copy(name = "Исправлено с упаковки", macros = seed.macros.copy(kcal = 99.0), favorite = true)
        a.saveProduct(corrected)
        exchange(a, fresh)
        assertEquals(corrected, fresh.data.products.single { it.id == seed.id })
        assertEquals(corrected, a.data.products.single { it.id == seed.id })
        assertEquals(a.exportSyncJson(), fresh.exportSyncJson())
    }

    @Test fun deletionsAndFieldClearsAreNotRevivedByStaleSnapshots() {
        val a = AppStore(context("a")) { 1000L }
        val b = AppStore(context("b")) { 1000L }
        a.saveProduct(food()); a.addEntry(entry("meal"))
        a.saveLog(DailyLog(day, weight = 80.0, waist = 90.0, steps = 5000))
        exchange(a, b)
        val old = b.exportSyncJson()
        a.saveLog(DailyLog(day, weight = 80.0, steps = 5000)) // Explicitly clear waist.
        a.deleteProduct(food().id); a.deleteEntry("meal")
        exchange(a, b)
        assertNull(b.data.logs.single().waist)
        assertTrue(b.data.entries.isEmpty())
        assertTrue(b.data.products.none { it.id == food().id })
        b.mergeSyncJson(old)
        assertNull(b.data.logs.single().waist)
        assertTrue(b.data.entries.isEmpty())
        a.deleteLog(day)
        exchange(a, b); b.mergeSyncJson(old)
        assertTrue(b.data.logs.isEmpty())
        b.saveLog(DailyLog(day, weight = 79.0)) // Intentional fresh row does not revive old indicators.
        exchange(a, b)
        assertEquals(DailyLog(day, weight = 79.0), a.data.logs.single())
    }

    @Test fun mergeIsAssociativeCommutativeAndIdempotentIncludingEqualStampTies() {
        val base = AppData(listOf(food()))
        val initial = SyncMerge.initial(base, base.products, "seed-a", 1)
        val aData = base.copy(entries = listOf(entry("a")))
        val bData = base.copy(logs = listOf(DailyLog(day, weight = 80.0)))
        val cData = base.copy(goals = Goals(Macros(kcal = 2100.0)))
        val a = SyncMerge.change(base, aData, initial, "device-a", 100)
        val b = SyncMerge.change(base, bData, initial, "device-b", 100)
        val c = SyncMerge.change(base, cData, initial, "device-c", 90)
        assertEquals(SyncMerge.merge(a, b), SyncMerge.merge(b, a))
        assertEquals(SyncMerge.merge(SyncMerge.merge(a, b), c), SyncMerge.merge(a, SyncMerge.merge(b, c)))
        assertEquals(a, SyncMerge.merge(a, a))
        val x = SyncMerge.change(base, base.copy(products = listOf(food().copy(name = "А"))), initial, "same", 200)
        val y = SyncMerge.change(base, base.copy(products = listOf(food().copy(name = "Б"))), initial, "same", 200)
        assertEquals(SyncMerge.merge(x, y), SyncMerge.merge(y, x))
    }

    @Test fun migrationBacksUpExactV1AndPreservesDeletedSeedsAndAllHistory() {
        val ctx = context("legacy")
        val catalog = DataJson.decodeCatalog(application.assets.open("products.json").bufferedReader().use { it.readText() })
        val deletedSeed = catalog.products.first().id
        val legacy = catalog.copy(products = catalog.products.drop(1) + food(), entries = listOf(entry("old")),
            logs = listOf(DailyLog(day, weight = 80.0, waist = 91.0, sleep = 7.5, training = "Прогулка")),
            goals = Goals(Macros(2000.0, 100.0, 70.0, 240.0), 75.0))
        val original = DataJson.encode(legacy).toByteArray(Charsets.UTF_8)
        File(ctx.filesDir, AppStore.FILE_NAME).writeBytes(original)
        val migrated = AppStore(ctx) { 1000L }
        assertFalse(migrated.loadError, migrated.isReadOnly)
        assertEquals(legacy.products, migrated.data.products)
        assertEquals(legacy.entries, migrated.data.entries)
        assertEquals(legacy.logs, migrated.data.logs)
        assertEquals(legacy.goals, migrated.data.goals)
        val backup = ctx.filesDir.listFiles()!!.single { it.name.startsWith("balance_data.v1-backup-") }
        assertTrue(original.contentEquals(backup.readBytes()))
        assertEquals(2, JSONObject(File(ctx.filesDir, AppStore.FILE_NAME).readText()).getInt("version"))
        val meta = SyncMerge.decode(migrated.exportSyncJson()).state
        assertEquals(SyncStamp.ZERO, meta.records["p/${catalog.products[1].id}"]!!.stamp)
        assertTrue(meta.records["p/$deletedSeed"]!!.deleted)
        val fresh = AppStore(context("fresh")) { 5000L }
        exchange(migrated, fresh)
        assertTrue(fresh.data.products.none { it.id == deletedSeed })
        assertEquals(migrated.deviceId, AppStore(ctx).deviceId)
    }

    @Test fun replacingImportUsesNewLocalVersionsAndNeverClonesInstallationIdentity() {
        val a = AppStore(context("a")) { 10000L }
        val b = AppStore(context("b")) { 1L }
        a.saveProduct(food())
        val oldBackup = a.exportJson()
        a.addEntry(entry("later")); a.saveLog(DailyLog(day, weight = 80.0))
        exchange(a, b)
        val stale = b.exportSyncJson()
        val identity = b.deviceId
        val localRevision = b.localRevision.value
        b.importJson(oldBackup)
        assertEquals(identity, b.deviceId)
        assertNotEquals(a.deviceId, b.deviceId)
        assertEquals(localRevision + 1, b.localRevision.value)
        b.mergeSyncJson(stale)
        assertTrue(b.data.entries.isEmpty()); assertTrue(b.data.logs.isEmpty())
        exchange(a, b)
        assertTrue(a.data.entries.isEmpty()); assertTrue(a.data.logs.isEmpty())
        assertEquals(a.exportSyncJson(), b.exportSyncJson())
    }

    @Test fun observedFutureClockDoesNotBlockLaterLocalChangesAfterClockMovesBackwards() {
        val ca = context("a"); val cb = context("b")
        val a = AppStore(ca) { 4_000_000_000_000L }
        var bClock = 100L
        val b = AppStore(cb) { bClock }
        a.saveProduct(food().copy(name = "Первое"))
        b.mergeSyncJson(a.exportSyncJson())
        bClock = 1L
        b.saveProduct(food().copy(name = "После синхронизации"))
        a.mergeSyncJson(b.exportSyncJson())
        assertEquals("После синхронизации", a.data.products.single { it.id == food().id }.name)
        val reopened = AppStore(cb) { 0L }
        reopened.saveProduct(food().copy(name = "После перезапуска"))
        a.mergeSyncJson(reopened.exportSyncJson())
        assertEquals("После перезапуска", a.data.products.single { it.id == food().id }.name)
    }

    @Test fun cloudMergeDoesNotEmitLocalAutosyncRevisionAndSnapshotHashIsStable() {
        val a = AppStore(context("a")) { 1L }
        val b = AppStore(context("b")) { 2L }
        a.addEntry(entry("a"))
        val baseline = b.localRevision.value
        assertTrue(b.mergeSyncJson(a.exportSyncJson()))
        assertEquals(baseline, b.localRevision.value)
        val snapshot = b.exportSyncJson()
        val revision = b.revision.value
        assertFalse(b.mergeSyncJson(a.exportSyncJson()))
        assertEquals(revision, b.revision.value)
        assertEquals(snapshot, b.exportSyncJson())
        b.saveGoals(b.data.goals)
        assertEquals(snapshot, b.exportSyncJson())
        assertEquals(baseline, b.localRevision.value)
    }

    @Test fun malformedMetadataAndDiskFailureCannotPartiallyMergeOrAdvanceClocks() {
        val ca = context("a"); val cb = context("b")
        val a = AppStore(ca) { 100L }; val b = AppStore(cb) { 100L }
        a.addEntry(entry("remote"))
        val before = b.exportSyncJson(); val revision = b.revision.value
        val corrupt = JSONObject(a.exportSyncJson())
        corrupt.getJSONObject("sync").getJSONArray("records").getJSONObject(0).put("key", "unknown/record")
        try { b.mergeSyncJson(corrupt.toString()); fail("Malformed merge accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(before, b.exportSyncJson()); assertEquals(revision, b.revision.value)
        val directory = cb.filesDir
        val preserved = File(root, "preserved")
        assertTrue(directory.renameTo(preserved)); directory.writeText("Blocks storage directory")
        try { b.mergeSyncJson(a.exportSyncJson()); fail("Failed write accepted") } catch (_: IOException) { }
        assertEquals(before, b.exportSyncJson()); assertEquals(revision, b.revision.value)
    }

    @Test fun achievementDatesChooseEarliestAndExplicitResetStillConverges() {
        val base = AppData(emptyList())
        val initial = SyncMerge.initial(base, emptyList(), "origin", 1)
        val earlyData = base.copy(achievements = mapOf("first_food" to "2020-01-01"))
        val lateData = base.copy(achievements = mapOf("first_food" to "2020-01-03"))
        val early = SyncMerge.change(base, earlyData, initial, "a", 100)
        val late = SyncMerge.change(base, lateData, initial, "b", 200)
        val combined = SyncMerge.merge(early, late)
        assertEquals("2020-01-01", SyncMerge.materialize(combined).achievements["first_food"])
        val reset = SyncMerge.change(SyncMerge.materialize(combined), base, combined, "c", 300, replaceAll = true)
        assertTrue(SyncMerge.materialize(SyncMerge.merge(reset, early)).achievements.isEmpty())
        assertEquals(SyncMerge.merge(SyncMerge.merge(early, late), reset), SyncMerge.merge(early, SyncMerge.merge(late, reset)))
        val restored = SyncMerge.change(base, lateData, reset, "c", 301, replaceAll = true)
        assertEquals("2020-01-03", SyncMerge.materialize(SyncMerge.merge(restored, early)).achievements["first_food"])
    }

    @Test fun staleEditorOnlyAppliesFieldsChangedByUser() {
        val a = AppStore(context("a")) { 1000L }; val b = AppStore(context("b")) { 1000L }
        a.saveLog(DailyLog(day, weight = 80.0))
        exchange(a, b)
        val baseline = a.data.logs.single()
        val baselineGoals = a.data.goals
        b.saveLog(b.data.logs.single().copy(waist = 91.0))
        b.saveGoals(Goals(Macros(protein = 130.0)))
        a.mergeSyncJson(b.exportSyncJson())
        a.saveLog(baseline.copy(note = "Из открытой формы"), baseline)
        a.saveGoals(baselineGoals.copy(macros = baselineGoals.macros.copy(kcal = 2000.0)), baselineGoals)
        assertEquals(91.0, a.data.logs.single().waist!!, 0.0)
        assertEquals("Из открытой формы", a.data.logs.single().note)
        assertEquals(130.0, a.data.goals.macros.protein, 0.0)
        assertEquals(2000.0, a.data.goals.macros.kcal, 0.0)
        val newDay = "2020-01-02"
        b.saveLog(DailyLog(newDay, steps = 5000))
        a.mergeSyncJson(b.exportSyncJson())
        a.saveLog(DailyLog(newDay, note = "Новая форма была пустой"), baseline = null)
        assertEquals(5000, a.data.logs.single { it.date == newDay }.steps)
    }

    @Test fun randomizedOfflineEditsDeletesRestoresAndMessageOrdersConverge() {
        val random = Random(46129)
        val base = AppData(listOf(food()))
        val initial = SyncMerge.initial(base, base.products, "seed", 1)
        val devices = MutableList(3) { initial }
        val snapshots = mutableListOf(initial)
        repeat(120) { step ->
            val index = random.nextInt(devices.size)
            val state = devices[index]
            val before = SyncMerge.materialize(state)
            val date = "2020-01-${(1 + random.nextInt(5)).toString().padStart(2, '0')}"
            var replacing = false
            val after = when (random.nextInt(8)) {
                0 -> before.copy(entries = before.entries + entry("entry_$step"))
                1 -> before.copy(entries = before.entries.drop(1))
                2 -> {
                    val existing = before.logs.find { it.date == date } ?: DailyLog(date)
                    val log = if (random.nextBoolean()) existing.copy(weight = 60.0 + random.nextInt(30))
                        else existing.copy(waist = 70.0 + random.nextInt(30))
                    before.copy(logs = before.logs.filterNot { it.date == date } + log)
                }
                3 -> before.copy(logs = before.logs.filterNot { it.date == date })
                4 -> before.copy(goals = before.goals.copy(macros = before.goals.macros.copy(kcal = (random.nextInt(5) * 500).toDouble())))
                5 -> before.copy(products = if (random.nextBoolean()) emptyList() else listOf(food().copy(name = "Продукт $step")))
                6 -> {
                    replacing = true
                    SyncMerge.materialize(snapshots[random.nextInt(snapshots.size)])
                }
                else -> before.copy(settings = if (random.nextBoolean()) mapOf("theme" to "dark") else emptyMap())
            }
            val changed = SyncMerge.change(before, after, state, "device-$index", random.nextLong(1, 500), replacing)
            assertTrue("Local projection failed at step $step", SyncMerge.equivalent(after, SyncMerge.materialize(changed)))
            devices[index] = changed
            snapshots += changed
            if (random.nextBoolean()) {
                val receiver = random.nextInt(devices.size)
                devices[receiver] = SyncMerge.merge(devices[receiver], snapshots[random.nextInt(snapshots.size)])
                DataJson.validate(SyncMerge.materialize(devices[receiver]))
                snapshots += devices[receiver]
            }
        }
        val expected = snapshots.fold(initial, SyncMerge::merge)
        repeat(5) {
            val reordered = snapshots.shuffled(random).fold(initial, SyncMerge::merge)
            assertEquals(expected, reordered)
            assertEquals(SyncMerge.materialize(expected), SyncMerge.materialize(reordered))
            assertEquals(reordered, SyncMerge.merge(reordered, reordered))
        }
    }

    @Test fun deletedRowsCannotHideInvalidWhitespaceNotesInsideMetadata() {
        val store = AppStore(context("valid")) { 100L }
        val before = store.exportSyncJson()
        val base = AppData(emptyList())
        val initial = SyncMerge.initial(base, emptyList(), "origin", 1)
        for (field in listOf("note", "training")) {
            val malformed = initial.copy(records = initial.records + mapOf(
                "l/$day/$field" to SyncRecord(SyncStamp(2, 0, "remote"), value = JSONObject().put("v", " ".repeat(2_001)).toString()),
                "l/$day/@deleted" to SyncRecord(SyncStamp(3, 0, "remote"), deleted = true),
            ), clock = SyncStamp(3, 0, "remote"))
            val json = SyncMerge.encode(base, malformed)
            try { store.mergeSyncJson(json); fail("Hidden invalid field accepted") } catch (_: IllegalArgumentException) { }
            try { store.importJson(json); fail("Hidden invalid field imported") } catch (_: IllegalArgumentException) { }
            assertEquals(before, store.exportSyncJson())
        }
    }
}
