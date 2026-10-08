package ru.balance.offline

import android.app.DatePickerDialog
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

val Green: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Cream: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

private val LightPalette = lightColorScheme(
    primary = Color(0xFF285849), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1ECD4), onPrimaryContainer = Color(0xFF224C39),
    secondary = Color(0xFF795B30), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E3CB), onSecondaryContainer = Color(0xFF574020),
    tertiary = Color(0xFF52658B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFE7F7), onTertiaryContainer = Color(0xFF304263),
    background = Color(0xFFF7F7F0), onBackground = Color(0xFF1E3027),
    surface = Color(0xFFF7F7F0), onSurface = Color(0xFF1E3027),
    surfaceVariant = Color(0xFFE5E9DC), onSurfaceVariant = Color(0xFF58685C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFEF9),
    surfaceContainer = Color(0xFFEFF1E7), surfaceContainerHigh = Color(0xFFE7EBDF),
    surfaceContainerHighest = Color(0xFFE1E6D8),
    outline = Color(0xFF7B8A7D), outlineVariant = Color(0xFFCAD2C3),
    inverseSurface = Color(0xFF26362D), inverseOnSurface = Color(0xFFEAF0E4),
    inversePrimary = Color(0xFFAAD3B5), surfaceTint = Color(0xFF285849),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFACD5B7), onPrimary = Color(0xFF113A26),
    primaryContainer = Color(0xFF284D38), onPrimaryContainer = Color(0xFFCCEBD2),
    secondary = Color(0xFFE0BE8C), onSecondary = Color(0xFF3E2D14),
    secondaryContainer = Color(0xFF514026), onSecondaryContainer = Color(0xFFF9DFB8),
    tertiary = Color(0xFFB9C7ED), onTertiary = Color(0xFF26324A),
    tertiaryContainer = Color(0xFF394962), onTertiaryContainer = Color(0xFFDFE8FF),
    background = Color(0xFF111B16), onBackground = Color(0xFFE3EADD),
    surface = Color(0xFF111B16), onSurface = Color(0xFFE3EADD),
    surfaceVariant = Color(0xFF344438), onSurfaceVariant = Color(0xFFB9C7B8),
    surfaceContainerLowest = Color(0xFF0C1510), surfaceContainerLow = Color(0xFF1A2720),
    surfaceContainer = Color(0xFF202E26), surfaceContainerHigh = Color(0xFF29372E),
    surfaceContainerHighest = Color(0xFF344238),
    outline = Color(0xFF8A9B89), outlineVariant = Color(0xFF3E5042),
    inverseSurface = Color(0xFFE3EADD), inverseOnSurface = Color(0xFF25382B),
    inversePrimary = Color(0xFF285849), surfaceTint = Color(0xFFACD5B7),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)
fun number(v:Double, digits:Int=1):String = String.format(Locale.forLanguageTag("ru"), "%.${digits}f",v).removeSuffix(",0")
fun decimal(v:String):Double? = v.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
fun dateLabel(date:String):String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMMM yyyy",Locale.forLanguageTag("ru")))
fun sleepLabel(hours:Double):String { val m=(hours*60).roundToInt(); return "${m/60} ч ${m%60} мин" }

@Composable fun BalanceTheme(darkTheme:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=if(darkTheme)DarkPalette else LightPalette, content=content)
}
@Composable fun PageTitle(title:String,subtitle:String) {
    Column(Modifier.padding(top=10.dp,bottom=8.dp)) {
        Text(title,fontSize=30.sp,fontWeight=FontWeight.Bold,color=Ink)
        Spacer(Modifier.height(5.dp)); Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=Muted)
    }
}
@Composable fun Panel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content) }
}
@Composable fun MacroLine(m:Macros, large:Boolean=false) {
    val dark=MaterialTheme.colorScheme.background.luminance()<0.5f
    val proteinColor=if(dark)Color(0xFFA9C6EF)else Color(0xFF45658B)
    val fatColor=if(dark)Color(0xFFE6C18A)else Color(0xFF85602A)
    val carbColor=if(dark)Color(0xFFD5B2E4)else Color(0xFF80528E)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        listOf(Triple("ккал",m.kcal,Green),Triple("белки",m.protein,proteinColor),Triple("жиры",m.fat,fatColor),Triple("углеводы",m.carbs,carbColor)).forEach { (label,value,color) ->
            Column { Text(number(value),fontSize=if(large)24.sp else 18.sp,fontWeight=FontWeight.Bold,color=color); Text(if(label=="ккал")label else "$label, г",fontSize=11.sp,color=Muted) }
        }
    }
}
@Composable fun DateControl(date:String,onDate:(String)->Unit,arrows:Boolean=true) {
    val context=LocalContext.current
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        if(arrows) TextButton(onClick={onDate(LocalDate.parse(date).minusDays(1).toString())},modifier=Modifier.width(40.dp),contentPadding=PaddingValues(0.dp)) { Text("‹",fontSize=24.sp) }
        TextButton(modifier=Modifier.weight(1f),onClick={ val d=LocalDate.parse(date); DatePickerDialog(context,datePickerTheme(context),{_,y,m,day->onDate(LocalDate.of(y,m+1,day).toString())},d.year,d.monthValue-1,d.dayOfMonth).show() }) {
            Text(if(date==LocalDate.now().toString()) "Сегодня · ${dateLabel(date)}" else dateLabel(date),fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center,maxLines=2)
        }
        if(arrows) TextButton(onClick={onDate(LocalDate.parse(date).plusDays(1).toString())},modifier=Modifier.width(40.dp),contentPadding=PaddingValues(0.dp)) { Text("›",fontSize=24.sp) }
    }
}

fun datePickerTheme(context:Context):Int =
    if((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES)
        android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
@Composable fun EditorDialog(title:String,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).heightIn(max=760.dp).imePadding(),shape=RoundedCornerShape(28.dp),color=Cream) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                content()
            }
        }
    }
}
@Composable fun ErrorText(message:String?) { if(message!=null) Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
