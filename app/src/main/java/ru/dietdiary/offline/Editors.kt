package ru.dietdiary.offline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.roundToInt

// Display rounding must never alter an existing value when an editor is saved unchanged.
private fun inputNumber(value:Double):String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',')

@Composable private fun NumericField(value:String,onValue:(String)->Unit,label:String,modifier:Modifier=Modifier,integer:Boolean=false) {
    OutlinedTextField(value,onValue,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(integer)KeyboardType.Number else KeyboardType.Decimal),modifier=modifier.fillMaxWidth())
}
@Composable private fun Actions(onDismiss:()->Unit,onSave:()->Unit,enabled:Boolean=true,label:String="Сохранить") {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick=onDismiss,modifier=Modifier.weight(1f)){Text("Отмена")};Button(onClick=onSave,enabled=enabled,modifier=Modifier.weight(1f).testTag("editor_save")){Text(label)} }
}
@Composable fun PortionDialog(product:Product?,entry:Entry?,date:String,onDismiss:()->Unit,onSave:(Entry)->Unit,onDelete:(()->Unit)?) {
    val id=entry?.id?:product!!.id
    var grams by rememberSaveable(id){mutableStateOf(entry?.grams?.let{inputNumber(it)}?:"")}
    var meal by rememberSaveable(id){mutableStateOf(entry?.meal?:"Завтрак")}
    var day by rememberSaveable(id){mutableStateOf(entry?.date?:date)}
    var confirmDelete by remember {mutableStateOf(false)}
    val weight=decimal(grams);val valid=weight!=null&&weight>0&&weight<=100000
    val macros=entry?.per100?:product!!.macros
    EditorDialog(if(entry==null)"Рассчитать порцию" else "Изменить порцию",onDismiss) {
        Text(entry?.name?:product!!.name,fontWeight=FontWeight.Bold,fontSize=20.sp)
        Text("На 100 г: ${number(macros.kcal)} ккал · Б ${number(macros.protein)} · Ж ${number(macros.fat)} · У ${number(macros.carbs)}",fontSize=12.sp,color=Muted)
        if(entry==null&&!product!!.note.isBlank()) Text(product.note,color=Muted,fontSize=11.sp,maxLines=5)
        NumericField(grams,{grams=it},"Вес порции, г",Modifier.testTag("portion_grams"))
        if(grams.isNotBlank()&&!valid)ErrorText("Введите вес больше 0 и не больше 100 000 г")
        if(valid)Panel{MacroLine(macros.scaled(weight!!),true)}
        DateControl(day,{day=it},false)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("Завтрак","Обед","Ужин","Перекус").forEach{m->FilterChip(selected=m==meal,onClick={meal=m},label={Text(m)})}}
        Actions(onDismiss,{onSave(Entry(entry?.id?:UUID.randomUUID().toString(),day,meal,entry?.productId?:product!!.id,entry?.name?:product!!.name,weight!!,macros))},valid,if(entry==null)"В дневник" else "Сохранить")
        if(onDelete!=null)TextButton(onClick={confirmDelete=true}){Text("Удалить запись",color=MaterialTheme.colorScheme.error)}
    }
    if(confirmDelete)DeletePrompt("Удалить порцию из дневника?",{confirmDelete=false},{onDelete?.invoke()})
}

@Composable fun ProductDialog(product:Product?,onDismiss:()->Unit,onSave:(Product)->Unit,onDelete:(()->Unit)?) {
    var name by rememberSaveable(product?.id){mutableStateOf(product?.name?:"")}
    var category by rememberSaveable(product?.id){mutableStateOf(product?.category?:"Мои продукты")}
    var kcal by rememberSaveable(product?.id){mutableStateOf(product?.macros?.kcal?.let{inputNumber(it)}?:"")}
    var protein by rememberSaveable(product?.id){mutableStateOf(product?.macros?.protein?.let{inputNumber(it)}?:"")}
    var fat by rememberSaveable(product?.id){mutableStateOf(product?.macros?.fat?.let{inputNumber(it)}?:"")}
    var carbs by rememberSaveable(product?.id){mutableStateOf(product?.macros?.carbs?.let{inputNumber(it)}?:"")}
    var note by rememberSaveable(product?.id){mutableStateOf(product?.note?:"")}
    var favorite by rememberSaveable(product?.id){mutableStateOf(product?.favorite?:false)}
    var error by remember{mutableStateOf<String?>(null)};var confirmDelete by remember{mutableStateOf(false)}
    EditorDialog(if(product==null)"Новый продукт или блюдо" else "Редактировать КБЖУ",onDismiss) {
        OutlinedTextField(name,{name=it},label={Text("Название")},modifier=Modifier.fillMaxWidth().testTag("product_name"),singleLine=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=category=="Мои продукты",onClick={category="Мои продукты"},label={Text("Продукт")});FilterChip(selected=category=="Готовые блюда",onClick={category="Готовые блюда"},label={Text("Готовое блюдо")})}
        OutlinedTextField(category,{category=it},label={Text("Категория")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("Значения на 100 г — с упаковки или из рецепта. Укажите 0, если нутриента нет.",color=Muted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(kcal,{kcal=it},"Ккал",Modifier.weight(1f).testTag("product_kcal"));NumericField(protein,{protein=it},"Белки, г",Modifier.weight(1f).testTag("product_protein"))}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(fat,{fat=it},"Жиры, г",Modifier.weight(1f).testTag("product_fat"));NumericField(carbs,{carbs=it},"Углеводы, г",Modifier.weight(1f).testTag("product_carbs"))}
        OutlinedTextField(note,{note=it},label={Text("Примечание, состав (необязательно)")},modifier=Modifier.fillMaxWidth(),maxLines=4)
        Row {Checkbox(favorite,{favorite=it});Text("В избранное",Modifier.padding(top=12.dp))}
        ErrorText(error)
        Actions(onDismiss,{
            val values=listOf(kcal,protein,fat,carbs).map(::decimal)
            if(name.isBlank()||category.isBlank())error="Укажите название и категорию"
            else if(values.any{it==null}||values[0]!! !in 0.0..1000.0||values.drop(1).any{it!! !in 0.0..100.0})error="Укажите КБЖУ на 100 г: калории 0–1000, Б/Ж/У 0–100 г"
            else onSave(Product(product?.id?:UUID.randomUUID().toString(),name.trim(),category.trim(),Macros(values[0]!!,values[1]!!,values[2]!!,values[3]!!),note.trim(),favorite))
        })
        if(onDelete!=null)TextButton(onClick={confirmDelete=true}){Text("Удалить из базы",color=MaterialTheme.colorScheme.error)}
        if(product!=null)Text("Старые записи питания сохранят свои значения.",fontSize=11.sp,color=Muted)
    }
    if(confirmDelete)DeletePrompt("Удалить продукт? Его записи в дневнике останутся.",{confirmDelete=false},{onDelete?.invoke()})
}

@Composable fun LogDialog(store:AppStore,initialDate:String,onDismiss:()->Unit,onSave:(DailyLog)->Unit,onDelete:(String)->Unit) {
    var day by rememberSaveable{mutableStateOf(initialDate)}
    val log=store.data.logs.find{it.date==day}
    var weight by rememberSaveable(day){mutableStateOf(log?.weight?.let{inputNumber(it)}?:"")}
    var waist by rememberSaveable(day){mutableStateOf(log?.waist?.let{inputNumber(it)}?:"")}
    var steps by rememberSaveable(day){mutableStateOf(log?.steps?.toString()?:"")}
    var hours by rememberSaveable(day){mutableStateOf(log?.sleep?.let{((it*60).roundToInt()/60).toString()}?:"")}
    var minutes by rememberSaveable(day){mutableStateOf(log?.sleep?.let{((it*60).roundToInt()%60).toString()}?:"")}
    var bodyFat by rememberSaveable(day){mutableStateOf(log?.bodyFat?.let{inputNumber(it)}?:"")}
    var training by rememberSaveable(day){mutableStateOf(log?.training?:"")}
    var calories by rememberSaveable(day){mutableStateOf(log?.calories?.let{inputNumber(it)}?:"")}
    var protein by rememberSaveable(day){mutableStateOf(log?.protein?.let{inputNumber(it)}?:"")}
    var note by rememberSaveable(day){mutableStateOf(log?.note?:"")}
    var error by remember{mutableStateOf<String?>(null)};var confirmDelete by remember{mutableStateOf(false)}
    EditorDialog("Запись дня",onDismiss) {
        DateControl(day,{day=it;error=null},false)
        Text("Заполните любые поля. Остальные оставьте пустыми.",color=Muted,fontSize=13.sp)
        if(log!=null)Text("На эту дату уже есть запись — редактируем её.",color=Green,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(weight,{weight=it},"Вес, кг",Modifier.weight(1f).testTag("log_weight"));NumericField(waist,{waist=it},"Талия, см",Modifier.weight(1f).testTag("log_waist"))}
        NumericField(steps,{steps=it},"Шаги",Modifier.testTag("log_steps"),true)
        Text("Сон",fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(hours,{hours=it},"Часы",Modifier.weight(1f).testTag("log_sleep_hours"),true);NumericField(minutes,{minutes=it},"Минуты",Modifier.weight(1f).testTag("log_sleep_minutes"),true)}
        NumericField(bodyFat,{bodyFat=it},"Жир по весам, % (необязательно)")
        Text("Процент жира с весов — ориентир для тренда, не точное измерение.",color=Muted,fontSize=11.sp)
        OutlinedTextField(training,{training=it},label={Text("Силовая тренировка")},placeholder={Text("Например: присед 3×10, гантели 12 кг")},modifier=Modifier.fillMaxWidth(),maxLines=3)
        Text("Итоги питания вручную",fontWeight=FontWeight.SemiBold)
        Text("Если заполнить, эти значения заменят сумму еды за день. Оставьте пустыми для автоматического подсчёта.",color=Muted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(calories,{calories=it},"Калории за день",Modifier.weight(1f));NumericField(protein,{protein=it},"Белок за день, г",Modifier.weight(1f))}
        OutlinedTextField(note,{note=it},label={Text("Заметки, самочувствие")},modifier=Modifier.fillMaxWidth(),maxLines=5)
        ErrorText(error)
        Actions(onDismiss,{
            try {
                fun optional(text:String,min:Double,max:Double,label:String):Double? { if(text.isBlank())return null;val v=decimal(text);require(v!=null&&v in min..max){"$label: допустимо от ${number(min)} до ${number(max)}"};return v }
                val w=optional(weight,1.0,500.0,"Вес");val t=optional(waist,20.0,400.0,"Талия")
                val s=if(steps.isBlank())null else steps.trim().toIntOrNull().also{require(it!=null&&it in 0..200000){"Шаги: целое число от 0 до 200 000"}}
                val sleep=if(hours.isBlank()&&minutes.isBlank())null else {
                    val h=if(hours.isBlank())0 else hours.toIntOrNull();val m=if(minutes.isBlank())0 else minutes.toIntOrNull()
                    require(h!=null&&m!=null&&h in 0..24&&m in 0..59&&h*60+m<=1440){"Сон: 0–24 часа, минуты 0–59"};h+m/60.0
                }
                val b=optional(bodyFat,0.0,100.0,"Процент жира");val k=optional(calories,0.0,100000.0,"Калории");val p=optional(protein,0.0,10000.0,"Белок")
                require(listOf(w,t,s,sleep,b,k,p).any{it!=null}||training.isNotBlank()||note.isNotBlank()){ "Добавьте хотя бы один показатель или заметку" }
                onSave(DailyLog(date=day,weight=w,waist=t,steps=s,sleep=sleep,note=note.trim(),bodyFat=b,training=training.trim(),calories=k,protein=p))
            }catch(e:IllegalArgumentException){error=e.message}
        })
        if(log!=null)TextButton(onClick={confirmDelete=true}){Text("Удалить день из дневника",color=MaterialTheme.colorScheme.error)}
    }
    if(confirmDelete)DeletePrompt("Удалить показатели за ${dateLabel(day)}? Питание останется.",{confirmDelete=false},{onDelete(day)})
}

@Composable fun GoalsDialog(goals:Goals,onDismiss:()->Unit,onSave:(Goals)->Unit) {
    fun initial(n:Double)=if(n>0)inputNumber(n)else ""
    var kcal by rememberSaveable{mutableStateOf(initial(goals.macros.kcal))};var protein by rememberSaveable{mutableStateOf(initial(goals.macros.protein))};var fat by rememberSaveable{mutableStateOf(initial(goals.macros.fat))};var carbs by rememberSaveable{mutableStateOf(initial(goals.macros.carbs))};var weight by rememberSaveable{mutableStateOf(goals.weight?.let{number(it)}?:"")};var error by remember{mutableStateOf<String?>(null)}
    EditorDialog("Личные цели",onDismiss) {
        Text("Все цели необязательны. Пустое поле означает, что цель не задана.",color=Muted)
        NumericField(kcal,{kcal=it},"Калории в день")
        NumericField(protein,{protein=it},"Белки в день, г")
        NumericField(fat,{fat=it},"Жиры в день, г")
        NumericField(carbs,{carbs=it},"Углеводы в день, г")
        NumericField(weight,{weight=it},"Желаемый вес, кг")
        ErrorText(error)
        Actions(onDismiss,{
            val values=listOf(kcal,protein,fat,carbs).map{if(it.isBlank())0.0 else decimal(it)};val w=if(weight.isBlank())null else decimal(weight)
            if(values.any{it==null}||values[0]!! !in 0.0..10000.0||values.drop(1).any{it!! !in 0.0..1000.0})error="Проверьте цели: калории 0–10 000, БЖУ 0–1000 г"
            else if(weight.isNotBlank()&&(w==null||w !in 1.0..500.0))error="Желаемый вес: от 1 до 500 кг"
            else onSave(Goals(Macros(values[0]!!,values[1]!!,values[2]!!,values[3]!!),w))
        })
    }
}
@Composable private fun DeletePrompt(message:String,onDismiss:()->Unit,onDelete:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("Удалить?")},text={Text(message)},confirmButton={TextButton(onClick=onDelete){Text("Удалить",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}
