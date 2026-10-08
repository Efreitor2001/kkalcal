package ru.balance.offline

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun CatalogScreen(store:AppStore,onSelect:(Product)->Unit,onEdit:(Product)->Unit,onNew:()->Unit,onRecipe:()->Unit,picking:Boolean=false) {
    var query by rememberSaveable{mutableStateOf("")};var category by rememberSaveable{mutableStateOf("Все")}
    val products=store.data.products
    val filtered=products.filter{p-> (category=="Все"||category==p.category||(category=="Избранное"&&p.favorite)) && p.name.lowercase().replace('ё','е').contains(query.trim().lowercase().replace('ё','е')) }.sortedWith(compareByDescending<Product>{it.favorite}.thenBy{it.name})
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        PageTitle(if(picking)"Выберите еду" else "Продукты и блюда", "${products.size} в базе · КБЖУ на 100 г")
        OutlinedTextField(query,{query=it},label={Text("Поиск продукта или блюда")},modifier=Modifier.fillMaxWidth().testTag("product_search"),singleLine=true,shape=RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf("Все","Избранное")+products.map{it.category}.distinct().sorted()).forEach{cat->FilterChip(selected=cat==category,onClick={category=cat},label={Text(cat)})}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onNew,modifier=Modifier.weight(1f).testTag("new_product")){Text("+ Продукт / блюдо",fontSize=12.sp)}
            OutlinedButton(onClick=onRecipe,modifier=Modifier.weight(1f)){Text("По рецепту",fontSize=12.sp)}
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=20.dp)) {
            if(filtered.isEmpty())item { Panel { Text("Ничего не найдено",fontWeight=FontWeight.Bold);Text("Измените поиск или добавьте свой продукт либо готовое блюдо.",color=Muted) } }
            items(filtered,key={it.id}) { product ->
                Surface(onClick={onSelect(product)},color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(20.dp),modifier=Modifier.testTag("food_${product.id}")) {
                    Column(Modifier.fillMaxWidth().padding(start=16.dp,top=14.dp,end=10.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) { Text((if(product.favorite)"★ " else "")+product.name,fontWeight=FontWeight.SemiBold);Text(product.category,color=Muted,fontSize=11.sp) }
                            TextButton(onClick={onEdit(product)},contentPadding=PaddingValues(6.dp)){Text("Править",fontSize=11.sp)}
                        }
                        Text("${number(product.macros.kcal)} ккал   ·   Б ${number(product.macros.protein)}   Ж ${number(product.macros.fat)}   У ${number(product.macros.carbs)}",fontSize=12.sp,color=Green)
                    }
                }
            }
        }
    }
}
