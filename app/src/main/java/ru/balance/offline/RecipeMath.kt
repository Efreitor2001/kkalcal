package ru.balance.offline

internal data class RecipeIngredient(
    val key: String,
    val productId: String,
    val name: String,
    val macros: Macros,
    val grams: Double
)

internal fun recipeIngredientsTotals(ingredients: List<RecipeIngredient>): Macros =
    ingredients.fold(Macros()) { result, ingredient -> result + ingredient.macros.scaled(ingredient.grams) }

/** The output weight is explicitly measured; raw ingredient weight is never substituted. */
internal fun recipeNutritionPer100(totals: Macros, outputWeight: Double?): Macros? {
    if (outputWeight == null || !outputWeight.isFinite() || outputWeight <= 0.0 || outputWeight > 100_000.0) return null
    return Macros(
        kcal = totals.kcal * 100.0 / outputWeight,
        protein = totals.protein * 100.0 / outputWeight,
        fat = totals.fat * 100.0 / outputWeight,
        carbs = totals.carbs * 100.0 / outputWeight
    )
}

internal fun recipeValidMacros(macros: Macros): Boolean =
    macros.kcal.isFinite() && macros.kcal in 0.0..1000.0 &&
        listOf(macros.protein, macros.fat, macros.carbs).all { it.isFinite() && it in 0.0..100.0 }
