package ru.dietdiary.offline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Collator

@Composable fun CatalogScreen(store:AppStore,onSelect:(Product)->Unit,onEdit:(Product)->Unit,onNew:()->Unit,onRecipe:()->Unit,picking:Boolean=false,onInputFocusChange:(Boolean)->Unit={}) {
    val currentFocusCallback by rememberUpdatedState(onInputFocusChange)
    DisposableEffect(Unit) { onDispose { currentFocusCallback(false) } }
    var query by rememberSaveable{mutableStateOf("")};var category by rememberSaveable{mutableStateOf("Все")}
    val products=store.data.products
    val locale=appLocale()
    val collator=remember(locale){Collator.getInstance(locale)}
    // A shared seed category uses its built-in label. A category present only on
    // custom foods keeps the user's text, even if it matches an old seed label.
    val categoryLabels=products.groupBy{it.category}.mapValues { (canonical,group) ->
        group.firstNotNullOfOrNull { it.localizedCategory().takeIf { label -> label!=canonical } } ?: canonical
    }
    val search=query.trim().lowercase(locale).replace('ё','е')
    val filtered=products.filter{p-> (category=="Все"||category==p.category||(category=="Избранное"&&p.favorite)) && listOf(p.localizedName(),p.name).any { it.lowercase(locale).replace('ё','е').contains(search) } }.sortedWith(compareByDescending<Product>{it.favorite}.thenComparator{a,b->collator.compare(a.localizedName(),b.localizedName())})
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        PageTitle(if(picking)tr(R.string.core_select_food) else tr(R.string.core_foods_dishes), tr(R.string.core_catalog_count, number(products.size.toDouble(), 0)))
        OutlinedTextField(query,{query=it},label={Text(tr(R.string.core_search_food))},modifier=Modifier.fillMaxWidth().onFocusChanged { currentFocusCallback(it.isFocused) }.testTag("product_search"),singleLine=true,shape=RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf("Все","Избранное")+categoryLabels.keys.sortedWith{a,b->collator.compare(categoryLabels[a],categoryLabels[b])}).forEach{cat->FilterChip(selected=cat==category,onClick={category=cat},label={Text(when(cat) { "Все" -> tr(R.string.core_all); "Избранное" -> tr(R.string.core_favorites); else -> categoryLabels[cat]?:cat })})}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onNew,modifier=Modifier.weight(1f).testTag("new_product")){Text(tr(R.string.core_new_food_button),fontSize=12.sp)}
            OutlinedButton(onClick=onRecipe,modifier=Modifier.weight(1f)){Text(tr(R.string.core_recipe_button),fontSize=12.sp)}
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=20.dp)) {
            if(filtered.isEmpty())item { Panel { Text(tr(R.string.core_no_results),fontWeight=FontWeight.Bold);Text(tr(R.string.core_no_results_help),color=Muted) } }
            items(filtered,key={it.id}) { product ->
                Surface(onClick={onSelect(product)},color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(20.dp),modifier=Modifier.testTag("food_${product.id}")) {
                    Column(Modifier.fillMaxWidth().padding(start=16.dp,top=14.dp,end=10.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) { Text((if(product.favorite)"★ " else "")+product.localizedName(),fontWeight=FontWeight.SemiBold);Text(product.localizedCategory(),color=Muted,fontSize=11.sp) }
                            TextButton(onClick={onEdit(product)},contentPadding=PaddingValues(6.dp)){Text(tr(R.string.core_edit),fontSize=11.sp)}
                        }
                        Text(tr(R.string.core_catalog_nutrition, number(product.macros.kcal), number(product.macros.protein), number(product.macros.fat), number(product.macros.carbs)),fontSize=12.sp,color=Green)
                    }
                }
            }
            if(!picking)item { AdCard(AdPlacement.PRODUCTS) }
        }
    }
}
