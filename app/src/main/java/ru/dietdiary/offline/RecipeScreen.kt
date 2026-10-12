package ru.dietdiary.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun RecipeScreen(store: AppStore, onBack: () -> Unit, onSaved: (Product) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var outputText by rememberSaveable { mutableStateOf("") }
    var ingredientsJson by rememberSaveable { mutableStateOf("[]") }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var pendingJson by rememberSaveable { mutableStateOf("") }
    var gramsText by rememberSaveable { mutableStateOf("") }
    var editingKey by rememberSaveable { mutableStateOf("") }
    var saveError by rememberSaveable { mutableStateOf("") }
    val ingredients = remember(ingredientsJson) { recipeReadIngredients(ingredientsJson) }
    val totals = remember(ingredients) {
        recipeIngredientsTotals(ingredients)
    }
    val rawWeight = ingredients.sumOf { it.grams }
    val outputWeight = recipeNumber(outputText)
    val outputValid = outputWeight != null && outputWeight > 0.0 && outputWeight <= 100_000.0
    val per100 = if (ingredients.isNotEmpty()) recipeNutritionPer100(totals, outputWeight) else null
    val macrosValid = per100 != null && recipeValidMacros(per100)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
        ) {
            item {
                TextButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                    BackNavigationLabel(tr(R.string.secondary_recipe_back))
                }
                Text(tr(R.string.secondary_my_recipe), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(R.string.secondary_recipe_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text(tr(R.string.secondary_recipe_name)) },
                    placeholder = { Text(tr(R.string.secondary_recipe_example)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(R.string.secondary_ingredients), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(java.text.NumberFormat.getIntegerInstance(appLocale()).format(ingredients.size), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ingredients.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text(tr(R.string.secondary_start_ingredients), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(tr(R.string.secondary_ingredients_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            items(ingredients, key = { it.key }) { ingredient ->
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(localizedProductName(ingredient.productId, ingredient.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val portion = ingredient.macros.scaled(ingredient.grams)
                        Text(tr(R.string.secondary_portion_energy, recipeFormat(ingredient.grams), recipeFormat(portion.kcal)),
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        Text(tr(R.string.secondary_portion_macros, recipeFormat(portion.protein), recipeFormat(portion.fat), recipeFormat(portion.carbs)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(modifier = Modifier.weight(1f), onClick = {
                                pendingJson = recipeWriteIngredients(listOf(ingredient))
                                gramsText = formatLocalizedInputNumber(ingredient.grams, appLocale())
                                editingKey = ingredient.key
                            }) { Text(tr(R.string.secondary_edit_weight)) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(modifier = Modifier.weight(1f), onClick = {
                                ingredientsJson = recipeWriteIngredients(ingredients.filterNot { it.key == ingredient.key })
                            }) { Text(tr(R.string.secondary_delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(R.string.secondary_add_ingredient))
                }
            }
            if (ingredients.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(tr(R.string.secondary_whole_recipe), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            RecipeMacroSummary(totals)
                            HorizontalDivider()
                            Text(tr(R.string.secondary_ingredients_weight, recipeFormat(rawWeight)), style = MaterialTheme.typography.bodyMedium)
                            Text(tr(R.string.secondary_cooked_weight_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = outputText,
                    onValueChange = { outputText = it.take(16) },
                    label = { Text(tr(R.string.secondary_cooked_weight)) },
                    placeholder = { Text(tr(R.string.secondary_cooked_weight_placeholder)) },
                    supportingText = {
                        Text(if (outputText.isNotBlank() && !outputValid) tr(R.string.secondary_cooked_weight_error)
                        else tr(R.string.secondary_weight_not_automatic))
                    },
                    isError = outputText.isNotBlank() && !outputValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }
            if (per100 != null) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(tr(R.string.secondary_recipe_per_hundred), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            RecipeMacroSummary(per100)
                            if (!macrosValid) {
                                Text(tr(R.string.secondary_recipe_macros_error),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val readyMacros = per100
                        if (name.trim().isNotEmpty() && readyMacros != null && recipeValidMacros(readyMacros)) {
                            val product = Product(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                // Stable stored category; localized only when displayed.
                                category = "Мои блюда",
                                macros = readyMacros,
                                note = tr(R.string.secondary_recipe_note, recipeFormat(outputWeight!!),
                                    ingredients.joinToString("; ") {
                                        tr(R.string.secondary_recipe_ingredient_note, localizedProductName(it.productId, it.name), recipeFormat(it.grams))
                                    })
                            )
                            runCatching { store.saveProduct(product) }
                                .onSuccess { saveError = ""; onSaved(product) }
                                .onFailure { saveError = it.message ?: tr(R.string.secondary_recipe_save_error) }
                        }
                    },
                    enabled = name.trim().isNotEmpty() && ingredients.isNotEmpty() && outputValid && macrosValid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text(tr(R.string.secondary_save_my_recipes)) }
                if (saveError.isNotBlank()) {
                    Text(saveError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(tr(R.string.secondary_recipe_saved_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showPicker) {
        RecipeProductPicker(
            products = store.data.products,
            onDismiss = { showPicker = false },
            onSelect = { product ->
                pendingJson = recipeWriteIngredients(listOf(RecipeIngredient(
                    key = UUID.randomUUID().toString(), productId = product.id,
                    name = product.name, macros = product.macros, grams = 0.0
                )))
                gramsText = ""
                editingKey = ""
                showPicker = false
            }
        )
    }
    val pending = remember(pendingJson) { recipeReadIngredients(pendingJson).firstOrNull() }
    if (pending != null) {
        val grams = recipeNumber(gramsText)
        val valid = grams != null && grams > 0.0 && grams <= 100_000.0
        AlertDialog(
            onDismissRequest = { pendingJson = "" },
            title = { Text(localizedProductName(pending.productId, pending.name)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(R.string.secondary_ingredient_raw_weight))
                    OutlinedTextField(
                        value = gramsText,
                        onValueChange = { gramsText = it.take(16) },
                        label = { Text(tr(R.string.secondary_grams)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = gramsText.isNotBlank() && !valid,
                        supportingText = { Text(tr(R.string.secondary_weight_limits)) }
                    )
                    if (valid) RecipeMacroSummary(pending.macros.scaled(grams!!))
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    if (grams != null && valid) {
                        val next = pending.copy(grams = grams)
                        ingredientsJson = recipeWriteIngredients(if (editingKey.isBlank()) ingredients + next
                        else ingredients.map { if (it.key == editingKey) next else it })
                        pendingJson = ""
                    }
                }) { Text(if (editingKey.isBlank()) tr(R.string.secondary_add) else tr(R.string.secondary_save)) }
            },
            dismissButton = { TextButton(onClick = { pendingJson = "" }) { Text(tr(R.string.secondary_cancel)) } }
        )
    }
}

@Composable
private fun RecipeMacroSummary(macros: Macros) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr(R.string.secondary_energy, recipeFormat(macros.kcal)), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(tr(R.string.secondary_metric_protein) to macros.protein, tr(R.string.secondary_metric_fat) to macros.fat, tr(R.string.secondary_metric_carbs) to macros.carbs).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(tr(R.string.secondary_grams_value, recipeFormat(value)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RecipeProductPicker(products: List<Product>, onDismiss: () -> Unit, onSelect: (Product) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(products, query, appLocale()) {
        val normalized = query.trim().lowercase(appLocale()).replace('ё', 'е')
        val collator = java.text.Collator.getInstance(appLocale())
        products.filter {
            it.localizedName().lowercase(appLocale()).replace('ё', 'е').contains(normalized) ||
                it.localizedCategory().lowercase(appLocale()).replace('ё', 'е').contains(normalized)
        }.sortedWith { first, second -> collator.compare(first.localizedName(), second.localizedName()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.secondary_choose_ingredient)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(tr(R.string.secondary_search_food)) }, modifier = Modifier.fillMaxWidth())
                if (filtered.isEmpty()) {
                    Text(tr(R.string.secondary_recipe_no_food))
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(filtered, key = { it.id }) { product ->
                        TextButton(onClick = { onSelect(product) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(product.localizedName(), style = MaterialTheme.typography.bodyLarge)
                                Text(tr(R.string.secondary_food_per_hundred, product.localizedCategory(), recipeFormat(product.macros.kcal)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.secondary_close)) } }
    )
}

private fun recipeNumber(text: String): Double? = parseLocalizedNumber(text)

private fun recipeFormat(value: Double): String = java.text.NumberFormat.getNumberInstance(appLocale()).apply {
    isGroupingUsed = false
    minimumFractionDigits = 0
    maximumFractionDigits = 1
}.format(value)

private fun recipeWriteIngredients(ingredients: List<RecipeIngredient>): String = JSONArray().apply {
    ingredients.forEach { ingredient ->
        put(JSONObject().apply {
            put("key", ingredient.key)
            put("productId", ingredient.productId)
            put("name", ingredient.name)
            put("grams", ingredient.grams)
            put("kcal", ingredient.macros.kcal)
            put("protein", ingredient.macros.protein)
            put("fat", ingredient.macros.fat)
            put("carbs", ingredient.macros.carbs)
        })
    }
}.toString()

private fun recipeReadIngredients(json: String): List<RecipeIngredient> = runCatching {
    val array = JSONArray(json)
    List(array.length()) { index ->
        val obj = array.getJSONObject(index)
        RecipeIngredient(
            key = obj.getString("key"), productId = obj.getString("productId"), name = obj.getString("name"),
            grams = obj.getDouble("grams"),
            macros = Macros(kcal = obj.getDouble("kcal"), protein = obj.getDouble("protein"),
                fat = obj.getDouble("fat"), carbs = obj.getDouble("carbs"))
        )
    }
}.getOrDefault(emptyList())
