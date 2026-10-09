package ru.dietdiary.offline

import android.app.DatePickerDialog
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun StatsScreen(store: AppStore) {
    var metricName by rememberSaveable { mutableStateOf(StatsMetric.WEIGHT.name) }
    var period by rememberSaveable { mutableStateOf("week") }
    var customStart by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(29).toString()) }
    var customEnd by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showMetrics by remember { mutableStateOf(false) }
    val metric = StatsMetric.valueOf(metricName)
    val today = LocalDate.now()
    val context = LocalContext.current
    val isNight = isSystemInDarkTheme()
    val end = if (period == "custom") LocalDate.parse(customEnd) else today
    val start = when (period) {
        "month" -> today.minusDays(29)
        "year" -> today.minusYears(1).plusDays(1)
        "custom" -> LocalDate.parse(customStart)
        else -> today.minusDays(6)
    }
    val rangeValid = !end.isBefore(start)
    val appData = store.data
    val allPoints = remember(appData.entries, appData.logs, metric) { statsAggregate(appData, metric) }
    val points = remember(allPoints, start, end) { statsPointsInRange(allPoints, start, end) }
    var selectedDate by rememberSaveable(metricName, period, customStart, customEnd) { mutableStateOf("") }
    val selected = points.firstOrNull { it.date.toString() == selectedDate }
    val latest = points.lastOrNull()
    val (current, previous) = statsTodayAndYesterday(allPoints, today)
    val average = points.takeIf { it.isNotEmpty() }?.map { it.value }?.average()

    fun pickDate(currentDate: LocalDate, onPicked: (LocalDate) -> Unit) {
        val pickerTheme = if (isNight) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        DatePickerDialog(context, pickerTheme, { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
            currentDate.year, currentDate.monthValue - 1, currentDate.dayOfMonth).apply {
            datePicker.maxDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Динамика", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Ваши записи в понятных графиках", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Column {
                    Text("Показатель", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { showMetrics = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${metric.title} · ${metric.unit}  ▾", style = MaterialTheme.typography.titleMedium)
                    }
                    DropdownMenu(expanded = showMetrics, onDismissRequest = { showMetrics = false }) {
                        StatsMetric.entries.forEach { candidate ->
                            DropdownMenuItem(text = { Text("${candidate.title} · ${candidate.unit}") }, onClick = {
                                metricName = candidate.name
                                showMetrics = false
                            })
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("week" to "Неделя", "month" to "Месяц", "year" to "Год", "custom" to "Свой период").forEach { (key, title) ->
                        FilterChip(selected = period == key, onClick = { period = key }, label = { Text(title) })
                    }
                }
                if (period == "custom") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                            pickDate(LocalDate.parse(customStart)) { customStart = it.toString() }
                        }) { Text("С ${statsShortDate(start)}") }
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                            pickDate(LocalDate.parse(customEnd)) { customEnd = it.toString() }
                        }) { Text("По ${statsShortDate(end)}") }
                    }
                }
                Text(if (rangeValid) "${statsFullDate(start)} — ${statsFullDate(end)}" else "Начало периода должно быть не позже конца",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rangeValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            if (rangeValid) {
                item {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${metric.title} за период", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (metric == StatsMetric.WEIGHT && appData.goals.weight != null) {
                                Text("Цель ${statsValue(appData.goals.weight, StatsMetric.WEIGHT)} кг",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            if (latest == null) {
                                Text("Пока нет записей", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                Text(if (metric.food) "Добавьте еду в дневник или выберите другой период."
                                else "Добавьте ${metric.title.lowercase(Locale.ROOT)} в дневник или выберите другой период.",
                                    style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("${statsValue(latest.value, metric)} ${metric.unit}",
                                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                                Text("Последняя запись · ${statsFullDate(latest.date)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                StatsChart(points, start, end, metric, selectedDate) { selectedDate = it.date.toString() }
                                if (selected != null) {
                                    Text("${statsFullDate(selected.date)}: ${statsValue(selected.value, metric)} ${metric.unit}",
                                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                } else {
                                    Text("Нажмите на график, чтобы выбрать запись", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!metric.bars && points.size > 1) {
                                    Text("Линия соединяет записи. Между ними измерений нет.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (metric == StatsMetric.BODY_FAT) {
                                Text("Показания весов: ориентир для тренда.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (metric.food) {
                                Text(if (metric == StatsMetric.KCAL || metric == StatsMetric.PROTEIN)
                                    "Ручной итог дня заменяет сумму еды. Без ручного итога учитываются продукты; без записей день пропущен."
                                else "Показаны суммы по внесённой еде. День без записей не считается нулём.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("Дни без записи пропущены и не участвуют в среднем.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Сегодня и вчера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${statsFullDate(today)} / ${statsFullDate(today.minusDays(1))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (current != null) "Сегодня: ${statsValue(current.value, metric)} ${metric.unit}"
                            else "Нет записи за сегодня", style = MaterialTheme.typography.bodyLarge)
                            Text(if (previous != null) "Вчера: ${statsValue(previous.value, metric)} ${metric.unit}"
                            else "Нет записи за вчера", style = MaterialTheme.typography.bodyLarge)
                            if (current != null && previous != null) {
                                HorizontalDivider()
                                Text("Изменение: ${statsSigned(current.value - previous.value, metric)} ${metric.unit}",
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (metric.food || metric == StatsMetric.STEPS || metric == StatsMetric.SLEEP) {
                                    Text("Сравнение по текущим записям: сегодня данные могут быть ещё неполными.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (points.isNotEmpty()) {
                    item {
                        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Итоги периода", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                StatsSummaryRow("Среднее по дням с данными", "${statsValue(average!!, metric)} ${metric.unit}")
                                StatsSummaryRow("Дней с данными", "${points.size} из ${end.toEpochDay() - start.toEpochDay() + 1}")
                                StatsSummaryRow("Минимум", "${statsValue(points.minOf { it.value }, metric)} ${metric.unit}")
                                StatsSummaryRow("Максимум", "${statsValue(points.maxOf { it.value }, metric)} ${metric.unit}")
                                HorizontalDivider()
                                if (points.size >= 2) {
                                    StatsSummaryRow("От первой до последней записи",
                                        "${statsSigned(points.last().value - points.first().value, metric)} ${metric.unit}")
                                    Text("${statsFullDate(points.first().date)} → ${statsFullDate(points.last().date)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("Для изменения за период нужны записи хотя бы за два дня.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        Text("История", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(if (points.size > 14) "Последние 14 дней с данными. Для ранних записей выберите свой период."
                        else "Все дни с данными за выбранный период", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(points.takeLast(14).asReversed(), key = { it.date.toString() }) { point ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatsSummaryRow(statsFullDate(point.date), "${statsValue(point.value, metric)} ${metric.unit}")
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun StatsSummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun StatsChart(
    points: List<StatsPoint>, start: LocalDate, end: LocalDate, metric: StatsMetric,
    selectedDate: String, onSelect: (StatsPoint) -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val right = with(density) { 14.dp.toPx() }
    val labelSize = with(density) { 10.sp.toPx() }
    val top = max(with(density) { 12.dp.toPx() }, labelSize)
    val bottom = labelSize * 2 + with(density) { 16.dp.toPx() }
    val chartHeight = 220.dp + with(density) { (labelSize * 2).toDp() }
    val axis = remember(points, metric) { statsAxis(points, metric.bars) }
    val daySpan = max(1L, end.toEpochDay() - start.toEpochDay())
    val dateTicks = remember(start, end) {
        val span = end.toEpochDay() - start.toEpochDay()
        listOf(start, start.plusDays(span / 2), end).distinct()
    }
    val paint = remember(labelColor, labelSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = labelColor.toArgb(); textSize = labelSize }
    }
    val tickCount = ((axis.high - axis.low) / axis.step).roundToInt().coerceIn(1, 8)
    val widestLabel = (0..tickCount).maxOf { paint.measureText(statsAxisLabel(axis.low + it * axis.step)) }
    val left = max(with(density) { 44.dp.toPx() }, widestLabel + with(density) { 12.dp.toPx() })
    val description = "${metric.title}: ${points.size} дней с данными, " +
        "от ${statsFullDate(start)} до ${statsFullDate(end)}. " +
        "Минимум ${statsValue(points.minOf { it.value }, metric)}, максимум ${statsValue(points.maxOf { it.value }, metric)} ${metric.unit}. Значения перечислены в истории."
    Canvas(Modifier.fillMaxWidth().height(chartHeight).semantics { contentDescription = description }
        .pointerInput(points, start, end, metric, left, right) {
            detectTapGestures { location ->
                val available = (size.width - left - right).coerceAtLeast(1f)
                val fraction = ((location.x - left) / available).coerceIn(0f, 1f)
                val targetDay = start.toEpochDay() + fraction.toDouble() * daySpan
                points.minByOrNull { abs(it.date.toEpochDay() - targetDay) }?.let(onSelect)
            }
        }) {
        val width = (size.width - left - right).coerceAtLeast(1f)
        val height = (size.height - top - bottom).coerceAtLeast(1f)
        fun x(date: LocalDate) = left + (statsDayFraction(date, start, end) * width).toFloat()
        fun y(value: Double) = top + ((axis.high - value) / (axis.high - axis.low) * height).toFloat()
        for (tick in 0..tickCount) {
            val value = axis.low + tick * axis.step
            val py = y(value)
            drawLine(gridColor, Offset(left, py), Offset(left + width, py), 1.dp.toPx())
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(statsAxisLabel(value), left - 12.dp.toPx(), py + labelSize / 3, paint)
        }
        val allDateLabelsWidth = dateTicks.sumOf { paint.measureText(statsShortDate(it)).toDouble() }.toFloat()
        val visibleTicks = if (allDateLabelsWidth + 24.dp.toPx() <= width) dateTicks else listOf(start, end).distinct()
        val stackDates = start != end && paint.measureText(statsShortDate(start)) + paint.measureText(statsShortDate(end)) + 12.dp.toPx() > width
        visibleTicks.forEachIndexed { index, date ->
            paint.textAlign = when {
                start == end -> Paint.Align.CENTER
                index == 0 -> Paint.Align.LEFT
                index == visibleTicks.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val labelY = top + height + labelSize + 8.dp.toPx() + if (stackDates && index > 0) labelSize else 0f
            drawContext.canvas.nativeCanvas.drawText(statsShortDate(date), x(date), labelY, paint)
        }
        val selected = points.firstOrNull { it.date.toString() == selectedDate }
        if (selected != null) {
            drawLine(lineColor.copy(alpha = 0.28f), Offset(x(selected.date), top), Offset(x(selected.date), top + height), 1.dp.toPx())
        }
        if (metric.bars) {
            val barWidth = (width / (daySpan + 1).toFloat() * 0.64f).coerceIn(1.dp.toPx(), 18.dp.toPx())
            points.forEach { point ->
                val px = x(point.date)
                val py = y(point.value)
                val barHeight = (y(0.0) - py).coerceAtLeast(0f)
                if (point.value == 0.0) {
                    // A recorded zero is a real point, unlike an absent date.
                    drawCircle(lineColor, 2.dp.toPx(), Offset(px, y(0.0)))
                } else {
                    drawRect(lineColor.copy(alpha = if (point.date.toString() == selectedDate) 1f else 0.68f),
                        Offset(px - barWidth / 2, py), Size(barWidth, barHeight))
                }
            }
        } else {
            val path = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.date), y(point.value)) else path.lineTo(x(point.date), y(point.value))
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx()))
            points.forEach { point ->
                drawCircle(background, 4.dp.toPx(), Offset(x(point.date), y(point.value)))
                drawCircle(lineColor, 3.dp.toPx(), Offset(x(point.date), y(point.value)))
            }
        }
        if (selected != null) {
            drawCircle(background, 6.dp.toPx(), Offset(x(selected.date), y(selected.value)))
            drawCircle(lineColor, 4.dp.toPx(), Offset(x(selected.date), y(selected.value)))
        }
    }
}

private val statsLocale = Locale.forLanguageTag("ru-RU")
private fun statsValue(value: Double, metric: StatsMetric): String =
    String.format(statsLocale, if (metric == StatsMetric.STEPS || metric == StatsMetric.KCAL) "%,.0f" else "%,.1f", value)

private fun statsSigned(value: Double, metric: StatsMetric): String {
    val threshold = if (metric == StatsMetric.STEPS || metric == StatsMetric.KCAL) 0.5 else 0.05
    return if (abs(value) < threshold) statsValue(0.0, metric) else (if (value > 0) "+" else "−") + statsValue(abs(value), metric)
}

private fun statsAxisLabel(value: Double): String = when {
    abs(value) >= 1_000_000 -> String.format(statsLocale, "%.1f млн", value / 1_000_000).replace(",0 ", " ")
    abs(value) >= 10_000 -> String.format(statsLocale, "%.0f тыс", value / 1000)
    else -> String.format(statsLocale, "%.1f", value).removeSuffix(",0")
}

private fun statsShortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd.MM", statsLocale))
private fun statsFullDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", statsLocale))
