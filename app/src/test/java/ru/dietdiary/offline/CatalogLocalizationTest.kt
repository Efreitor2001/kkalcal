package ru.dietdiary.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uses the shipped catalog, not a second synthetic copy of its names or nutrition values. */
class CatalogLocalizationTest {
    private fun catalog(): AppData {
        val asset = listOf(File("src/main/assets/products.json"), File("app/src/main/assets/products.json"))
            .firstOrNull { it.isFile } ?: error("Cannot find the shipped products.json")
        return DataJson.decodeCatalog(asset.readText(Charsets.UTF_8))
    }

    @Test fun all127SeedFoodsHaveEnglishDisplayWithoutChangingTheirPersistedValues() {
        val data = catalog()
        assertEquals(127, data.products.size)
        val before = DataJson.encode(data)
        data.products.forEach { product ->
            assertNotEquals("Missing name translation: ${product.id}", product.name, product.localizedName())
            assertNotEquals("Missing category translation: ${product.id}", product.category, product.localizedCategory())
            assertNotEquals("Missing source note translation: ${product.id}", product.note, product.localizedNote())
            assertFalse(product.localizedName().contains(Regex("[А-Яа-яЁё]")))
            val source = Regex("FDC ([0-9]+)").find(product.note)?.groupValues?.get(1)
            requireNotNull(source) { "Seed source ID is missing: ${product.id}" }
            assertTrue("Source ID changed: ${product.id}", product.localizedNote().contains("FDC $source"))
        }
        assertEquals(before, DataJson.encode(data))
        assertEquals(data, DataJson.decode(before))
        val apple = data.products.single { it.id == "seed_9003" }
        assertEquals(Macros(52.0, 0.26, 0.17, 13.8), apple.macros)
    }

    @Test fun customFoodMatchingRussianSeedTextIsNotSilentlyTranslated() {
        val seed = catalog().products.first()
        val custom = seed.copy(id = "my-custom-food")
        assertEquals(custom.name, custom.localizedName())
        assertEquals(custom.category, custom.localizedCategory())
        assertEquals(custom.note, custom.localizedNote())
    }

    @Test fun eachEditedFieldRemainsUserTextWithoutBlockingOtherSeedFieldTranslations() {
        val seed = catalog().products.first()
        val renamed = seed.copy(name = "Моё яблоко 🍏")
        assertEquals(renamed.name, renamed.localizedName())
        assertEquals(seed.localizedCategory(), renamed.localizedCategory())
        assertEquals(seed.localizedNote(), renamed.localizedNote())

        val customCategory = seed.copy(category = "Семейные продукты")
        assertEquals(customCategory.category, customCategory.localizedCategory())
        assertEquals(seed.localizedName(), customCategory.localizedName())

        val annotated = seed.copy(note = "Упаковка: 45 ккал; не переводить")
        assertEquals(annotated.note, annotated.localizedNote())
        assertEquals(seed.localizedName(), annotated.localizedName())
    }

    @Test fun nutritionEditsAndFavoritesDoNotRemoveSeedDisplayLocalization() {
        val seed = catalog().products.first()
        val edited = seed.copy(macros = Macros(49.0, 0.29, 0.19, 12.75), favorite = true)
        assertEquals(seed.localizedName(), edited.localizedName())
        assertEquals(seed.localizedCategory(), edited.localizedCategory())
        assertEquals(seed.localizedNote(), edited.localizedNote())
        assertEquals(Macros(49.0, 0.29, 0.19, 12.75), edited.macros)
        assertTrue(edited.favorite)
    }

    @Test fun historicalEntryRetainsSnapshotEvenAfterItsCatalogProductIsRenamedAndChanged() {
        val seed = catalog().products.first()
        val entry = Entry("history", "2026-10-01", "Завтрак", seed.id, seed.name, 125.5, seed.macros)
        val snapshotTotal = entry.total
        val edited = seed.copy(name = "Новая этикетка", macros = Macros(70.0, 3.0, 2.0, 10.0))
        val data = AppData(listOf(edited), entries = listOf(entry))
        val before = DataJson.encode(data)
        assertEquals(seed.localizedName(), entry.localizedName())
        assertEquals("Breakfast", mealLabel(entry.meal))
        assertEquals(seed.name, entry.name)
        assertEquals(seed.macros, entry.per100)
        assertEquals(snapshotTotal, entry.total)
        assertEquals(before, DataJson.encode(data))
        assertEquals(data, DataJson.decode(before))
    }

    @Test fun userNamedHistoricalEntriesAndUnknownMealsStayVerbatim() {
        val seed = catalog().products.first()
        val entry = Entry("custom-history", "2026-10-01", "После тренировки", seed.id, "Яблоко из сада", 50.0, seed.macros)
        assertEquals(entry.name, entry.localizedName())
        assertEquals(entry.meal, mealLabel(entry.meal))
        val matchingNameButUnknownId = entry.copy(productId = "removed-custom-product", name = seed.name)
        assertEquals(seed.name, matchingNameButUnknownId.localizedName())
        assertEquals("Семейное меню", categoryLabel("Семейное меню"))
    }
}
