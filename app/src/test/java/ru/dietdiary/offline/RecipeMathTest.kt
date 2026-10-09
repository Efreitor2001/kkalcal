package ru.dietdiary.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMathTest {
    @Test fun totalsUseIngredientSnapshotsAndEdibleWeight() {
        val ingredients = listOf(
            RecipeIngredient("1", "rice", "Рис", Macros(350.0, 7.0, 1.0, 78.0), 80.0),
            RecipeIngredient("2", "oil", "Масло", Macros(900.0, 0.0, 100.0, 0.0), 10.0)
        )
        val total = recipeIngredientsTotals(ingredients)
        assertEquals(370.0, total.kcal, 0.000001)
        assertEquals(5.6, total.protein, 0.000001)
        assertEquals(10.8, total.fat, 0.000001)
        assertEquals(62.4, total.carbs, 0.000001)
    }

    @Test fun cookingWaterChangesPer100ButPreservesWholeDishNutrition() {
        val total = Macros(600.0, 30.0, 20.0, 70.0)
        val per100 = recipeNutritionPer100(total, 400.0)!!
        assertEquals(Macros(150.0, 7.5, 5.0, 17.5), per100)
        assertEquals(total, per100.scaled(400.0))
        assertEquals(225.0, per100.scaled(150.0).kcal, 0.000001)
    }

    @Test fun invalidOrMissingOutputWeightNeverFallsBackToIngredientWeight() {
        val total = Macros(600.0, 30.0, 20.0, 70.0)
        listOf<Double?>(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 100_001.0).forEach {
            assertNull(recipeNutritionPer100(total, it))
        }
        assertTrue(recipeNutritionPer100(total, 100_000.0) != null)
    }

    @Test fun implausiblePer100CannotBeSavedIncludingNonFiniteAndNegativeValues() {
        assertTrue(recipeValidMacros(Macros(1000.0, 100.0, 100.0, 100.0)))
        assertFalse(recipeValidMacros(recipeNutritionPer100(Macros(600.0), 10.0)!!))
        assertFalse(recipeValidMacros(Macros(100.0, 100.1)))
        assertFalse(recipeValidMacros(Macros(Double.NaN)))
        assertFalse(recipeValidMacros(Macros(100.0, fat = Double.POSITIVE_INFINITY)))
        assertFalse(recipeValidMacros(Macros(100.0, carbs = -0.1)))
    }
}
