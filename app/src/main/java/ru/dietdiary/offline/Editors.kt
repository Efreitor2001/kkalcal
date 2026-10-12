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
private fun inputNumber(value:Double):String {
    val symbols=java.text.DecimalFormatSymbols.getInstance(appLocale())
    return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().map { char ->
        when { char=='.' -> symbols.decimalSeparator; char in '0'..'9' -> (symbols.zeroDigit.code+char.digitToInt()).toChar(); else -> char }
    }.joinToString("")
}

// Share the tested localized parser, including valid pasted grouping, but reject fractions.
private fun inputInteger(text:String):Int? = parseLocalizedInteger(text)

@Composable private fun NumericField(value:String,onValue:(String)->Unit,label:String,modifier:Modifier=Modifier,integer:Boolean=false) {
    OutlinedTextField(value,onValue,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(integer)KeyboardType.Number else KeyboardType.Decimal),modifier=modifier.fillMaxWidth())
}
@Composable private fun Actions(onDismiss:()->Unit,onSave:()->Unit,enabled:Boolean=true,label:String=tr(R.string.core_save)) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick=onDismiss,modifier=Modifier.weight(1f)){Text(tr(R.string.core_cancel))};Button(onClick=onSave,enabled=enabled,modifier=Modifier.weight(1f).testTag("editor_save")){Text(label)} }
}
@Composable fun PortionDialog(product:Product?,entry:Entry?,date:String,onDismiss:()->Unit,onSave:(Entry)->Unit,onDelete:(()->Unit)?) {
    val id=entry?.id?:product!!.id
    var grams by rememberSaveable(id){mutableStateOf(entry?.grams?.let{inputNumber(it)}?:"")}
    var meal by rememberSaveable(id){mutableStateOf(entry?.meal?:"Завтрак")}
    var day by rememberSaveable(id){mutableStateOf(entry?.date?:date)}
    var confirmDelete by remember {mutableStateOf(false)}
    val weight=decimal(grams);val valid=weight!=null&&weight>0&&weight<=100000
    val macros=entry?.per100?:product!!.macros
    EditorDialog(if(entry==null)tr(R.string.core_calculate_portion) else tr(R.string.core_edit_portion),onDismiss) {
        Text(entry?.localizedName()?:product!!.localizedName(),fontWeight=FontWeight.Bold,fontSize=20.sp)
        Text(tr(R.string.core_per100_summary, number(macros.kcal), number(macros.protein), number(macros.fat), number(macros.carbs)),fontSize=12.sp,color=Muted)
        if(entry==null&&!product!!.note.isBlank()) Text(product.localizedNote(),color=Muted,fontSize=11.sp)
        NumericField(grams,{grams=it},tr(R.string.core_portion_weight),Modifier.testTag("portion_grams"))
        if(grams.isNotBlank()&&!valid)ErrorText(tr(R.string.core_portion_invalid))
        if(valid)Panel{MacroLine(macros.scaled(weight!!),true)}
        DateControl(day,{day=it},false)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("Завтрак","Обед","Ужин","Перекус").forEach{m->FilterChip(selected=m==meal,onClick={meal=m},label={Text(mealLabel(m))})}}
        Actions(onDismiss,{onSave(Entry(entry?.id?:UUID.randomUUID().toString(),day,meal,entry?.productId?:product!!.id,entry?.name?:product!!.name,weight!!,macros))},valid,if(entry==null)tr(R.string.core_add_to_diary) else tr(R.string.core_save))
        if(onDelete!=null)TextButton(onClick={confirmDelete=true}){Text(tr(R.string.core_delete_entry),color=MaterialTheme.colorScheme.error)}
    }
    if(confirmDelete)DeletePrompt(tr(R.string.core_delete_portion_question),{confirmDelete=false},{onDelete?.invoke()})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ProductDialog(product:Product?,onDismiss:()->Unit,onSave:(Product)->Unit,onDelete:(()->Unit)?) {
    // Activity recreation can change the display language while retaining an unfinished edit.
    // Compare against what the editor originally showed, not a newly translated current value.
    val initialName=rememberSaveable(product?.id){product?.localizedName()?:""}
    val initialNote=rememberSaveable(product?.id){product?.localizedNote()?:""}
    var name by rememberSaveable(product?.id){mutableStateOf(initialName)}
    var category by rememberSaveable(product?.id){mutableStateOf(product?.localizedCategory()?:categoryLabel("Мои продукты"))}
    var canonicalCategory by rememberSaveable(product?.id){mutableStateOf<String?>(product?.category?:"Мои продукты")}
    var kcal by rememberSaveable(product?.id){mutableStateOf(product?.macros?.kcal?.let{inputNumber(it)}?:"")}
    var protein by rememberSaveable(product?.id){mutableStateOf(product?.macros?.protein?.let{inputNumber(it)}?:"")}
    var fat by rememberSaveable(product?.id){mutableStateOf(product?.macros?.fat?.let{inputNumber(it)}?:"")}
    var carbs by rememberSaveable(product?.id){mutableStateOf(product?.macros?.carbs?.let{inputNumber(it)}?:"")}
    var note by rememberSaveable(product?.id){mutableStateOf(initialNote)}
    var favorite by rememberSaveable(product?.id){mutableStateOf(product?.favorite?:false)}
    var error by remember{mutableStateOf<String?>(null)};var confirmDelete by remember{mutableStateOf(false)}
    EditorDialog(if(product==null)tr(R.string.core_new_food) else tr(R.string.core_edit_nutrition),onDismiss) {
        OutlinedTextField(name,{name=it},label={Text(tr(R.string.core_name))},modifier=Modifier.fillMaxWidth().testTag("product_name"),singleLine=true)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=canonicalCategory=="Мои продукты",onClick={canonicalCategory="Мои продукты";category=categoryLabel("Мои продукты")},label={Text(tr(R.string.core_food))});FilterChip(selected=canonicalCategory=="Готовые блюда",onClick={canonicalCategory="Готовые блюда";category=categoryLabel("Готовые блюда")},label={Text(tr(R.string.core_dish))})}
        OutlinedTextField(category,{category=it;canonicalCategory=null},label={Text(tr(R.string.core_category))},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text(tr(R.string.core_per100_hint),color=Muted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(kcal,{kcal=it},tr(R.string.core_kcal),Modifier.weight(1f).testTag("product_kcal"));NumericField(protein,{protein=it},tr(R.string.core_protein_grams),Modifier.weight(1f).testTag("product_protein"))}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(fat,{fat=it},tr(R.string.core_fat_grams),Modifier.weight(1f).testTag("product_fat"));NumericField(carbs,{carbs=it},tr(R.string.core_carbs_grams),Modifier.weight(1f).testTag("product_carbs"))}
        OutlinedTextField(note,{note=it},label={Text(tr(R.string.core_food_note))},modifier=Modifier.fillMaxWidth(),maxLines=4)
        Row {Checkbox(favorite,{favorite=it});Text(tr(R.string.core_favorite),Modifier.padding(top=12.dp))}
        ErrorText(error)
        Actions(onDismiss,{
            val values=listOf(kcal,protein,fat,carbs).map(::decimal)
            if(name.isBlank()||category.isBlank())error=tr(R.string.core_name_category_required)
            else if(values.any{it==null}||values[0]!! !in 0.0..1000.0||values.drop(1).any{it!! !in 0.0..100.0})error=tr(R.string.core_nutrition_invalid)
            else onSave(Product(product?.id?:UUID.randomUUID().toString(),if(product!=null && name==initialName)product.name else name.trim(),
                canonicalCategory?:category.trim(),
                Macros(values[0]!!,values[1]!!,values[2]!!,values[3]!!),if(product!=null && note==initialNote)product.note else note.trim(),favorite))
        })
        if(onDelete!=null)TextButton(onClick={confirmDelete=true}){Text(tr(R.string.core_delete_food),color=MaterialTheme.colorScheme.error)}
        if(product!=null)Text(tr(R.string.core_old_entries_unchanged),fontSize=11.sp,color=Muted)
    }
    if(confirmDelete)DeletePrompt(tr(R.string.core_delete_food_question),{confirmDelete=false},{onDelete?.invoke()})
}

@Composable fun LogDialog(store:AppStore,initialDate:String,onDismiss:()->Unit,onSave:(DailyLog,DailyLog?)->Unit,onDelete:(String)->Unit) {
    var day by rememberSaveable{mutableStateOf(initialDate)}
    val baselineJson = rememberSaveable(day) { DataJson.encode(AppData(emptyList(), logs=store.data.logs.filter{it.date==day})) }
    val log = remember(baselineJson) { DataJson.decode(baselineJson).logs.firstOrNull() }
    var weight by rememberSaveable(day){mutableStateOf(log?.weight?.let{inputNumber(it)}?:"")}
    var waist by rememberSaveable(day){mutableStateOf(log?.waist?.let{inputNumber(it)}?:"")}
    var steps by rememberSaveable(day){mutableStateOf(log?.steps?.let{number(it.toDouble(),0)}?:"")}
    var hours by rememberSaveable(day){mutableStateOf(log?.sleep?.let{number(((it*60).roundToInt()/60).toDouble(),0)}?:"")}
    var minutes by rememberSaveable(day){mutableStateOf(log?.sleep?.let{number(((it*60).roundToInt()%60).toDouble(),0)}?:"")}
    var bodyFat by rememberSaveable(day){mutableStateOf(log?.bodyFat?.let{inputNumber(it)}?:"")}
    var training by rememberSaveable(day){mutableStateOf(log?.training?:"")}
    var calories by rememberSaveable(day){mutableStateOf(log?.calories?.let{inputNumber(it)}?:"")}
    var protein by rememberSaveable(day){mutableStateOf(log?.protein?.let{inputNumber(it)}?:"")}
    var note by rememberSaveable(day){mutableStateOf(log?.note?:"")}
    var error by remember{mutableStateOf<String?>(null)};var confirmDelete by remember{mutableStateOf(false)}
    EditorDialog(tr(R.string.core_day_entry),onDismiss) {
        DateControl(day,{day=it;error=null},false)
        Text(tr(R.string.core_optional_fields),color=Muted,fontSize=13.sp)
        if(log!=null)Text(tr(R.string.core_editing_existing),color=Green,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(weight,{weight=it},tr(R.string.core_weight_kg),Modifier.weight(1f).testTag("log_weight"));NumericField(waist,{waist=it},tr(R.string.core_waist_cm),Modifier.weight(1f).testTag("log_waist"))}
        NumericField(steps,{steps=it},tr(R.string.core_steps),Modifier.testTag("log_steps"),true)
        Text(tr(R.string.core_sleep),fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(hours,{hours=it},tr(R.string.core_hours),Modifier.weight(1f).testTag("log_sleep_hours"),true);NumericField(minutes,{minutes=it},tr(R.string.core_minutes),Modifier.weight(1f).testTag("log_sleep_minutes"),true)}
        NumericField(bodyFat,{bodyFat=it},tr(R.string.core_body_fat_optional))
        Text(tr(R.string.core_body_fat_hint),color=Muted,fontSize=11.sp)
        OutlinedTextField(training,{training=it},label={Text(tr(R.string.core_strength_training))},placeholder={Text(tr(R.string.core_training_example))},modifier=Modifier.fillMaxWidth(),maxLines=3)
        Text(tr(R.string.core_manual_nutrition),fontWeight=FontWeight.SemiBold)
        Text(tr(R.string.core_manual_nutrition_hint),color=Muted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NumericField(calories,{calories=it},tr(R.string.core_daily_calories),Modifier.weight(1f));NumericField(protein,{protein=it},tr(R.string.core_daily_protein),Modifier.weight(1f))}
        OutlinedTextField(note,{note=it},label={Text(tr(R.string.core_wellbeing_notes))},modifier=Modifier.fillMaxWidth(),maxLines=5)
        ErrorText(error)
        Actions(onDismiss,{
            try {
                fun optional(text:String,min:Double,max:Double,label:String):Double? { if(text.isBlank())return null;val v=decimal(text);require(v!=null&&v in min..max){tr(R.string.core_range_invalid, label, number(min), number(max))};return v }
                val w=optional(weight,1.0,500.0,tr(R.string.core_weight));val t=optional(waist,20.0,400.0,tr(R.string.core_waist))
                val s=if(steps.isBlank())null else inputInteger(steps).also{require(it!=null&&it in 0..200000){tr(R.string.core_steps_invalid)}}
                val sleep=if(hours.isBlank()&&minutes.isBlank())null else {
                    val h=if(hours.isBlank())0 else inputInteger(hours);val m=if(minutes.isBlank())0 else inputInteger(minutes)
                    require(h!=null&&m!=null&&h in 0..24&&m in 0..59&&h*60+m<=1440){tr(R.string.core_sleep_invalid)};h+m/60.0
                }
                val b=optional(bodyFat,0.0,100.0,tr(R.string.core_body_fat));val k=optional(calories,0.0,100000.0,tr(R.string.core_calories));val p=optional(protein,0.0,10000.0,tr(R.string.core_protein))
                require(listOf(w,t,s,sleep,b,k,p).any{it!=null}||training.isNotBlank()||note.isNotBlank()){ tr(R.string.core_entry_required) }
                onSave(DailyLog(date=day,weight=w,waist=t,steps=s,sleep=sleep,note=note.trim(),bodyFat=b,training=training.trim(),calories=k,protein=p),log)
            }catch(e:IllegalArgumentException){error=e.message}
        })
        if(log!=null)TextButton(onClick={confirmDelete=true}){Text(tr(R.string.core_delete_day),color=MaterialTheme.colorScheme.error)}
    }
    if(confirmDelete)DeletePrompt(tr(R.string.core_delete_day_question, dateLabel(day)),{confirmDelete=false},{onDelete(day)})
}

@Composable fun GoalsDialog(goals:Goals,onDismiss:()->Unit,onSave:(Goals,Goals)->Unit) {
    val baselineJson = rememberSaveable { DataJson.encode(AppData(emptyList(), goals=goals)) }
    val baseline = remember(baselineJson) { DataJson.decode(baselineJson).goals }
    fun initial(n:Double)=if(n>0)inputNumber(n)else ""
    var kcal by rememberSaveable{mutableStateOf(initial(goals.macros.kcal))};var protein by rememberSaveable{mutableStateOf(initial(goals.macros.protein))};var fat by rememberSaveable{mutableStateOf(initial(goals.macros.fat))};var carbs by rememberSaveable{mutableStateOf(initial(goals.macros.carbs))};var weight by rememberSaveable{mutableStateOf(goals.weight?.let{inputNumber(it)}?:"")};var error by remember{mutableStateOf<String?>(null)}
    EditorDialog(tr(R.string.core_personal_goals),onDismiss) {
        Text(tr(R.string.core_optional_goals),color=Muted)
        NumericField(kcal,{kcal=it},tr(R.string.core_calories_per_day))
        NumericField(protein,{protein=it},tr(R.string.core_protein_per_day))
        NumericField(fat,{fat=it},tr(R.string.core_fat_per_day))
        NumericField(carbs,{carbs=it},tr(R.string.core_carbs_per_day))
        NumericField(weight,{weight=it},tr(R.string.core_target_weight))
        ErrorText(error)
        Actions(onDismiss,{
            val values=listOf(kcal,protein,fat,carbs).map{if(it.isBlank())0.0 else decimal(it)};val w=if(weight.isBlank())null else decimal(weight)
            if(values.any{it==null}||values[0]!! !in 0.0..10000.0||values.drop(1).any{it!! !in 0.0..1000.0})error=tr(R.string.core_goals_invalid)
            else if(weight.isNotBlank()&&(w==null||w !in 1.0..500.0))error=tr(R.string.core_target_weight_invalid)
            else onSave(Goals(Macros(values[0]!!,values[1]!!,values[2]!!,values[3]!!),w),baseline)
        })
    }
}
@Composable private fun DeletePrompt(message:String,onDismiss:()->Unit,onDelete:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(tr(R.string.core_delete_question))},text={Text(message)},confirmButton={TextButton(onClick=onDelete){Text(tr(R.string.core_delete),color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick=onDismiss){Text(tr(R.string.core_cancel))}})
}
