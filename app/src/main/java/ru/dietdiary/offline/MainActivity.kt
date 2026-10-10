package ru.dietdiary.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val store=AppStore.get(applicationContext)
        DietAds.attachColdStart(this, savedInstanceState != null)
        runCatching { CloudSync.get(applicationContext).onAppStart() }
        setContent { DietDiaryTheme {
            val opening = DietAds.waitingForStartup
            Box(Modifier.fillMaxSize()) {
                Box(if(opening) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) { DietDiaryApp(store) }
                if(opening) {
                    SideEffect { DietAds.startupPanelVisible() }
                    Surface(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { DietAds.skipStartup() } }, color=MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
                            Text("Дневник диеты", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Калории, вес и прогресс", color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(28.dp))
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.height(20.dp))
                            TextButton(onClick={DietAds.skipStartup()}) { Text("Перейти в дневник") }
                        }
                    }
                } else SideEffect { DietAds.markDiaryVisible() }
            }
        } }
    }
    override fun onUserInteraction() { super.onUserInteraction(); DietAds.onUserInteraction() }
}

@Composable fun DietDiaryApp(store:AppStore) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var recipe by rememberSaveable { mutableStateOf(false) }
    var portionId by rememberSaveable { mutableStateOf<String?>(null) }
    var editEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var editProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var newProduct by rememberSaveable { mutableStateOf(false) }
    var logDate by rememberSaveable { mutableStateOf<String?>(null) }
    var showGoals by rememberSaveable { mutableStateOf(false) }
    var extraScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var backupEditing by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SideEffect { DietAds.setEditing(picker || recipe || portionId!=null || editEntryId!=null || editProductId!=null || newProduct || logDate!=null || showGoals || error!=null || extraScreen!=null || backupEditing || searchEditing) }
    val snack=remember { SnackbarHostState() }; val scope=rememberCoroutineScope()
    LaunchedEffect(store) { store.achievementEvents.collect { ids -> ids.forEach { id ->
        AchievementEngine.find(id)?.let { snack.showSnackbar("🏆 Достижение разблокировано! ${it.title} — ${it.unlockedMessage}") }
    } } }
    val navigationTextSize=if(LocalDensity.current.fontScale>1.3f)8.sp else 10.sp
    fun change(action:()->Unit) { try { action() } catch(e:Exception) { error=e.message?:"Не удалось сохранить данные" } }
    val selectedEntry=store.data.entries.find { it.id==editEntryId }
    val selectedProduct=store.data.products.find { it.id==portionId }
    BackHandler(picker||recipe) { picker=false;recipe=false }
    BackHandler(extraScreen!=null) { extraScreen=null }
    Scaffold(containerColor=Cream,snackbarHost={SnackbarHost(snack)},bottomBar={if(!recipe&&!picker) NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        listOf("День" to "◉","Продукты" to "▦","Дневник" to "▤","Графики" to "▥","Ещё" to "•••").forEachIndexed { i,p ->
            NavigationBarItem(selected=tab==i,onClick={tab=i;extraScreen=null},icon={NavGlyph(i)},label={Text(p.first,fontSize=navigationTextSize,maxLines=1,overflow=TextOverflow.Ellipsis)},modifier=Modifier.testTag("tab_$i"))
        }
    }}) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                extraScreen=="cloud" -> CloudScreen(onBack={extraScreen=null})
                extraScreen=="achievements" -> AchievementsScreen(store.data,onBack={extraScreen=null},footer={AdCard(AdPlacement.ACHIEVEMENTS)})
                extraScreen=="privacy" -> PrivacyScreen(onBack={extraScreen=null})
                recipe -> RecipeScreen(store,onBack={recipe=false},onSaved={recipe=false;tab=1;scope.launch{snack.showSnackbar("Блюдо сохранено в продуктах")}})
                picker -> Column {
                    TextButton(onClick={picker=false}) { Text("‹  Назад к дню") }
                    CatalogScreen(store,onSelect={portionId=it.id},onEdit={editProductId=it.id},onNew={newProduct=true},onRecipe={recipe=true},picking=true)
                }
                tab==0 -> DayScreen(store,date,{date=it},onAdd={picker=true},onEdit={editEntryId=it.id},onLog={logDate=date},onGoals={showGoals=true})
                tab==1 -> CatalogScreen(store,onSelect={portionId=it.id},onEdit={editProductId=it.id},onNew={newProduct=true},onRecipe={recipe=true},onInputFocusChange={searchEditing=it})
                tab==2 -> DiaryScreen(store,onEdit={logDate=it},onNew={logDate=LocalDate.now().toString()})
                tab==3 -> StatsScreen(store)
                else -> SettingsScreen(store,onGoals={showGoals=true},onCloud={extraScreen="cloud"},onAchievements={extraScreen="achievements"},onPrivacy={extraScreen="privacy"},onBusyChange={backupEditing=it},onError={error=it},onMessage={scope.launch{snack.showSnackbar(it)}})
            }
        }
    }
    if(selectedProduct!=null||selectedEntry!=null) {
        PortionDialog(product=selectedProduct,entry=selectedEntry,date=date,onDismiss={portionId=null;editEntryId=null},onSave={entry ->
            change { if(selectedEntry==null)store.addEntry(entry) else store.updateEntry(entry); portionId=null;editEntryId=null;picker=false;tab=0 }
        },onDelete=if(selectedEntry!=null)({change{store.deleteEntry(selectedEntry.id);editEntryId=null}})else null)
    }
    if(newProduct||editProductId!=null) ProductDialog(store.data.products.find {it.id==editProductId},onDismiss={newProduct=false;editProductId=null},onSave={p ->change{store.saveProduct(p);newProduct=false;editProductId=null}},onDelete=if(editProductId!=null)({change{store.deleteProduct(editProductId!!);editProductId=null}})else null)
    if(logDate!=null) LogDialog(store,logDate!!,onDismiss={logDate=null},onSave={log,baseline->change{store.saveLog(log,baseline);logDate=null}},onDelete={d->change{store.deleteLog(d);logDate=null}})
    if(showGoals) GoalsDialog(store.data.goals,onDismiss={showGoals=false},onSave={goals,baseline->change{store.saveGoals(goals,baseline);showGoals=false}})
    if(error!=null) AlertDialog(onDismissRequest={error=null},title={Text("Не удалось выполнить действие")},text={Text(error!!)},confirmButton={TextButton(onClick={error=null}){Text("Понятно")}})
}

@Composable private fun DayScreen(store:AppStore,date:String,onDate:(String)->Unit,onAdd:()->Unit,onEdit:(Entry)->Unit,onLog:()->Unit,onGoals:()->Unit) {
    val entries=store.data.entries.filter { it.date==date }
    val foodTotal=entries.fold(Macros()) { a,b->a+b.total };val goal=store.data.goals.macros
    val log=store.data.logs.find{it.date==date}
    val total=foodTotal.copy(kcal=log?.calories?:foodTotal.kcal,protein=log?.protein?:foodTotal.protein)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Дневник диеты","Калории, вес и прогресс") }
        if(store.loadError!=null)item { Panel { ErrorText(store.loadError);Text("Восстановление копии доступно в разделе «Ещё».") } }
        item { DateControl(date,onDate) }
        item { Surface(color=Color(0xFF285849),shape=androidx.compose.foundation.shape.RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("СЪЕДЕНО ЗА ДЕНЬ",color=Color(0xFFD3E3CE),letterSpacing=1.5.sp,fontSize=11.sp)
                Row { Text(number(total.kcal,0),fontSize=46.sp,fontWeight=FontWeight.Bold,color=Color.White);Text(" ккал",Modifier.padding(top=25.dp),color=Color(0xFFD3E3CE)) }
                if(goal.kcal>0) { LinearProgressIndicator(progress={(total.kcal/goal.kcal).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=Color(0xFFD9E8B8),trackColor=Color(0xFF547A64)); Text(if(total.kcal<=goal.kcal)"До цели ${number(goal.kcal-total.kcal,0)} ккал · цель ${number(goal.kcal,0)}" else "Выше цели на ${number(total.kcal-goal.kcal,0)} ккал",color=Color.White,fontSize=13.sp) }
                else Text("Цель можно задать в разделе «Ещё»",color=Color(0xFFD3E3CE),fontSize=13.sp)
            }
        } }
        item { Panel { MacroLine(total); if(log?.calories!=null||log?.protein!=null)Text("${if(log.calories!=null)"Калории" else ""}${if(log.calories!=null&&log.protein!=null)" и " else ""}${if(log.protein!=null)"белок" else ""} — итог вручную из дневника",fontSize=12.sp,color=Muted);if(goal.protein+goal.fat+goal.carbs>0)Text("Цели Б / Ж / У: ${if(goal.protein>0)number(goal.protein)else"—"} / ${if(goal.fat>0)number(goal.fat)else"—"} / ${if(goal.carbs>0)number(goal.carbs)else"—"} г",fontSize=12.sp,color=Muted) } }
        item { Button(onClick=onAdd,modifier=Modifier.fillMaxWidth().height(54.dp).testTag("add_food")) { Text("+  Добавить еду",fontSize=16.sp) } }
        if(entries.isEmpty()) item { Panel { Text("Начнём с первого приёма пищи",fontWeight=FontWeight.SemiBold);Text("Выберите продукт, введите граммы — КБЖУ посчитается сразу.",color=Muted) } }
        (listOf("Завтрак","Обед","Ужин","Перекус")+entries.map{it.meal}).distinct().forEach { meal ->
            val group=entries.filter{it.meal==meal}
            if(group.isNotEmpty()) {
                item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(meal,fontSize=20.sp,fontWeight=FontWeight.Bold);Text("${number(group.sumOf{it.total.kcal},0)} ккал",color=Muted) } }
                items(group,key={it.id}) { entry -> Surface(onClick={onEdit(entry)},color=MaterialTheme.colorScheme.surfaceContainerLow,shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(entry.name,fontWeight=FontWeight.SemiBold);Text("${number(entry.grams)} г · ${number(entry.total.kcal)} ккал",color=Green);Text("Б ${number(entry.total.protein)}   Ж ${number(entry.total.fat)}   У ${number(entry.total.carbs)}",color=Muted,fontSize=12.sp) } } }
            }
        }
        item { Panel { Text("Мой день",fontSize=20.sp,fontWeight=FontWeight.Bold);Text(log?.let{logSummary(it)}?:"Вес, талия, шаги, сон — запишите то, что знаете.",color=Muted);OutlinedButton(onClick=onLog,modifier=Modifier.fillMaxWidth()){Text(if(log==null)"Записать показатели" else "Изменить показатели")} } }
        item { Text("Вес и КБЖУ продукта должны относиться к одному состоянию: сырому, сухому или готовому.",fontSize=12.sp,color=Muted) }
        item(key = "day_ad") { AdCard(AdPlacement.DAY) }
    }
}

fun logSummary(l:DailyLog):String = listOfNotNull(l.weight?.let{"Вес ${number(it)} кг"},l.waist?.let{"Талия ${number(it)} см"},l.steps?.let{"$it шагов"},l.sleep?.let{sleepLabel(it)},l.bodyFat?.let{"Жир ${number(it)}%"},l.training.takeIf{it.isNotBlank()},l.calories?.let{"${number(it)} ккал вручную"},l.protein?.let{"Белок ${number(it)} г вручную"},l.note.takeIf{it.isNotBlank()}).joinToString(" · ")

@Composable private fun DiaryScreen(store:AppStore,onEdit:(String)->Unit,onNew:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { PageTitle("Мой дневник","Каждый день — в своём ритме") }
        item { Button(onClick=onNew,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("add_log")) { Text("+  Записать день") } }
        item { Text("Все показатели необязательны. Можно добавить только вес, сон или заметку. Нажмите на запись, чтобы изменить её.",color=Muted,style=MaterialTheme.typography.bodyMedium) }
        if(store.data.logs.isEmpty())item { Panel { Text("История начинается здесь",fontWeight=FontWeight.Bold);Text("Записывайте измерения и смотрите изменения на вкладке «Графики».",color=Muted) } }
        items(store.data.logs.sortedByDescending{it.date},key={it.date}) { log ->
            Surface(onClick={onEdit(log.date)},color=MaterialTheme.colorScheme.surfaceContainerLow,shape=androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(dateLabel(log.date),fontWeight=FontWeight.Bold,color=Green)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                        log.weight?.let{Column{Text("${number(it)} кг",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Вес",color=Muted,fontSize=12.sp)}}
                        log.waist?.let{Column{Text("${number(it)} см",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Талия",color=Muted,fontSize=12.sp)}}
                    }
                    val line=listOfNotNull(log.steps?.let{"$it шагов"},log.sleep?.let{"Сон ${sleepLabel(it)}"}).joinToString(" · ")
                    if(line.isNotEmpty())Text(line,color=Muted)
                    log.bodyFat?.let{Text("Жир по весам ${number(it)}%",color=Muted)}
                    if(log.training.isNotBlank())Text("Тренировка: ${log.training}")
                    if(log.calories!=null||log.protein!=null)Text(listOfNotNull(log.calories?.let{"${number(it)} ккал"},log.protein?.let{"Белок ${number(it)} г"}).joinToString(" · ")+" (вручную)",color=Muted)
                    if(log.note.isNotBlank())Text(log.note)
                }
            }
        }
        item { AdCard(AdPlacement.DIARY) }
    }
}

@Composable private fun SettingsScreen(store:AppStore,onGoals:()->Unit,onCloud:()->Unit,onAchievements:()->Unit,onPrivacy:()->Unit,onBusyChange:(Boolean)->Unit,onError:(String)->Unit,onMessage:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<String?>(null) };var busy by remember{mutableStateOf(false)}
    SideEffect { onBusyChange(busy || pendingImport!=null) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null) scope.launch {
        busy=true
        try { val json=store.exportJson(); withContext(Dispatchers.IO){ val stream=context.contentResolver.openOutputStream(uri)?:error("Файл недоступен");stream.bufferedWriter(Charsets.UTF_8).use{it.write(json)} };onMessage("Резервная копия сохранена") }catch(e:Exception){onError(e.message?:"Ошибка экспорта")}finally{busy=false}
    }}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch {
        busy=true
        try { pendingImport=withContext(Dispatchers.IO){ val stream=context.contentResolver.openInputStream(uri)?:error("Файл недоступен");stream.use{AppStore.readBackup(it)} } }catch(e:Exception){onError(e.message?:"Ошибка чтения")}finally{busy=false}
    }}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PageTitle("Дневник диеты","Настройки и сохранность данных")
        Panel { Text("Ваш прогресс",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Небольшие достижения за привычку вести дневник.",color=Muted);OutlinedButton(onClick=onAchievements,modifier=Modifier.fillMaxWidth().testTag("open_achievements")){Text("Достижения")} }
        Panel { Text("Google Drive",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Необязательное подключение для объединения дневника на ваших устройствах.",color=Muted);OutlinedButton(onClick=onCloud,modifier=Modifier.fillMaxWidth().testTag("open_cloud")){Text("Облачная синхронизация")} }
        Panel { Text("Личные цели",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Калории, БЖУ и желаемый вес задаются вручную. Любую цель можно оставить пустой.",color=Muted);Button(onClick=onGoals,modifier=Modifier.fillMaxWidth()){Text("Настроить цели")} }
        Panel { Text("Резервная копия",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Продукты, питание, дневник, цели и достижения — в одном файле. Сохраните копию перед переустановкой приложения.",color=Muted)
            OutlinedButton(onClick={DietAds.suppressNextEntry();export.launch("diet-diary-${LocalDate.now()}.json")},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Сохранить в файл")}
            OutlinedButton(onClick={DietAds.suppressNextEntry();import.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Восстановить из файла")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if(store.loadError!=null)Panel { ErrorText(store.loadError);Text("Запись заблокирована, чтобы сохранить исходный файл. Восстановите корректную резервную копию.") }
        Panel { Text("О приложении и данных",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Как хранятся записи, работает синхронизация и удаляются данные.",color=Muted);OutlinedButton(onClick=onPrivacy,modifier=Modifier.fillMaxWidth().testTag("open_privacy")){Text("Конфиденциальность и удаление данных")} }
        Panel { Text("Работает без интернета",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Вход в Google необязателен. Шаги и сон вводятся вручную. Сеть нужна для подключённой синхронизации и загрузки рекламы. Записи дневника в рекламную сеть не передаются.",color=Muted) }
        Panel { Text("О продуктах",fontWeight=FontWeight.Bold);Text("Встроенные значения — справочные, на 100 г съедобной части. Уточняйте их по упаковке. Изменение продукта не пересчитывает старые записи питания.",color=Muted);Text("Источник базы: USDA FoodData Central, SR Legacy. У конкретных продуктов указано состояние и описание источника.",fontSize=12.sp,color=Muted) }
        Text("Дневник диеты · ${BuildConfig.VERSION_NAME}",fontSize=12.sp,color=Muted)
        AdCard(AdPlacement.MORE)
    }
    if(pendingImport!=null) AlertDialog(onDismissRequest={pendingImport=null},title={Text("Восстановить копию?")},text={Text("Текущие продукты, дневник, цели и достижения будут заменены содержимым файла. Сначала сохраните текущую копию. При подключённой синхронизации восстановленные изменения передадутся и в Google Drive.")},confirmButton={TextButton(onClick={try{store.importJson(pendingImport!!);pendingImport=null;onMessage("Копия восстановлена")}catch(e:Exception){pendingImport=null;onError(e.message?:"Неверный файл")}}){Text("Заменить данные")}},dismissButton={TextButton(onClick={pendingImport=null}){Text("Отмена")}})
}
