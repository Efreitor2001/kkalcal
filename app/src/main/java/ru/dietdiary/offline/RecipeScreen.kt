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
import java.util.Locale
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
                    Text("← Назад")
                }
                Text("Моё блюдо", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ингредиенты взвешивайте до готовки; готовый вес нужен для порций. Добавьте масло и соусы.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("Название блюда") },
                    placeholder = { Text("Например, овощное рагу") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ингредиенты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${ingredients.size}", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ingredients.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("Начните с продуктов", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("Добавьте каждый ингредиент и укажите его вес в граммах.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            items(ingredients, key = { it.key }) { ingredient ->
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ingredient.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val portion = ingredient.macros.scaled(ingredient.grams)
                        Text("${recipeFormat(ingredient.grams)} г · ${recipeFormat(portion.kcal)} ккал",
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        Text("Б ${recipeFormat(portion.protein)} · Ж ${recipeFormat(portion.fat)} · У ${recipeFormat(portion.carbs)} г",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(modifier = Modifier.weight(1f), onClick = {
                                pendingJson = recipeWriteIngredients(listOf(ingredient))
                                gramsText = ingredient.grams.toString().replace('.', ',').removeSuffix(",0")
                                editingKey = ingredient.key
                            }) { Text("Изменить вес") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(modifier = Modifier.weight(1f), onClick = {
                                ingredientsJson = recipeWriteIngredients(ingredients.filterNot { it.key == ingredient.key })
                            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Добавить ингредиент")
                }
            }
            if (ingredients.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Во всём блюде", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            RecipeMacroSummary(totals)
                            HorizontalDivider()
                            Text("Вес ингредиентов: ${recipeFormat(rawWeight)} г", style = MaterialTheme.typography.bodyMedium)
                            Text("При готовке вес меняется из-за воды. Взвесьте готовое блюдо без посуды.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = outputText,
                    onValueChange = { outputText = it.take(16) },
                    label = { Text("Вес готового блюда, г") },
                    placeholder = { Text("Укажите после приготовления") },
                    supportingText = {
                        Text(if (outputText.isNotBlank() && !outputValid) "Введите вес больше 0 и не более 100 000 г"
                        else "Вес не подставляется автоматически")
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
                            Text("На 100 г готового блюда", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            RecipeMacroSummary(per100)
                            if (!macrosValid) {
                                Text("Проверьте готовый вес и ингредиенты: на 100 г допустимо до 1000 ккал и до 100 г каждого БЖУ.",
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
                                category = "Мои блюда",
                                macros = readyMacros,
                                note = "Выход: ${recipeFormat(outputWeight!!)} г. Состав: " + ingredients.joinToString("; ") {
                                    "${it.name} — ${recipeFormat(it.grams)} г"
                                }
                            )
                            runCatching { store.saveProduct(product) }
                                .onSuccess { saveError = ""; onSaved(product) }
                                .onFailure { saveError = it.message ?: "Не удалось сохранить блюдо" }
                        }
                    },
                    enabled = name.trim().isNotEmpty() && ingredients.isNotEmpty() && outputValid && macrosValid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Сохранить в «Мои блюда»") }
                if (saveError.isNotBlank()) {
                    Text(saveError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("Сохранённое блюдо можно добавлять в дневник по весу порции.",
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
            title = { Text(pending.name) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Вес ингредиента до готовки")
                    OutlinedTextField(
                        value = gramsText,
                        onValueChange = { gramsText = it.take(16) },
                        label = { Text("Граммы") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = gramsText.isNotBlank() && !valid,
                        supportingText = { Text("Больше 0 и не более 100 000 г") }
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
                }) { Text(if (editingKey.isBlank()) "Добавить" else "Сохранить") }
            },
            dismissButton = { TextButton(onClick = { pendingJson = "" }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun RecipeMacroSummary(macros: Macros) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${recipeFormat(macros.kcal)} ккал", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Белки" to macros.protein, "Жиры" to macros.fat, "Углеводы" to macros.carbs).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text("${recipeFormat(value)} г", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RecipeProductPicker(products: List<Product>, onDismiss: () -> Unit, onSelect: (Product) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(products, query) {
        val normalized = query.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        products.filter {
            it.name.lowercase(Locale.ROOT).replace('ё', 'е').contains(normalized) ||
                it.category.lowercase(Locale.ROOT).replace('ё', 'е').contains(normalized)
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите ингредиент") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Поиск продуктов") }, modifier = Modifier.fillMaxWidth())
                if (filtered.isEmpty()) {
                    Text("Ничего не найдено. Свой продукт можно добавить на вкладке «Продукты».")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(filtered, key = { it.id }) { product ->
                        TextButton(onClick = { onSelect(product) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(product.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${product.category} · ${recipeFormat(product.macros.kcal)} ккал / 100 г",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private fun recipeNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

private fun recipeFormat(value: Double): String = String.format(Locale.forLanguageTag("ru"), "%.1f", value).removeSuffix(",0")

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
