package ru.dietdiary.offline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun AchievementsScreen(
    data: AppData,
    onBack: () -> Unit,
    today: LocalDate = LocalDate.now(),
    footer: @Composable () -> Unit = {},
) {
    val progress = remember(data.entries, data.logs, data.achievements, today) {
        AchievementEngine.progress(data, today)
    }
    val earned = progress.count { it.isEarned }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("achievements_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹  Назад") }
            PageTitle("Достижения", "Маленькие шаги становятся привычкой")
        }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ВАШ ПРОГРЕСС", fontSize = 11.sp, letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$earned", fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("из ${progress.size} открыто", modifier = Modifier.weight(1f).padding(bottom = 7.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    LinearProgressIndicator(progress = { earned.toFloat() / progress.size }, modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                    Text("Полученные достижения остаются с вами, даже если вы исправите записи.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Text("За регулярность и заботу о дневнике", fontWeight = FontWeight.SemiBold, color = Ink)
            Text("Учитываются сохранённые записи до сегодняшнего дня включительно.",
                modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        items(progress, key = { it.definition.id }) { item -> AchievementCard(item) }
        item { footer() }
    }
}

@Composable
private fun AchievementCard(progress: AchievementProgress) {
    val achievement = progress.definition
    val hidden = progress.isHidden
    val title = if (hidden) "Скрытое достижение" else achievement.title
    val description = if (hidden) "Продолжайте вести дневник, чтобы открыть." else achievement.description
    Panel(Modifier.testTag("achievement_${achievement.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AchievementBadge(achievement.symbol, hidden, progress.isEarned)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(when {
                    progress.isEarned -> "Получено"
                    hidden -> "Скрыто · пока закрыто"
                    achievement.kind == AchievementKind.PROGRESSIVE -> "В процессе · пока закрыто"
                    else -> "Пока закрыто"
                }, fontSize = 11.sp, color = if (progress.isEarned) Green else Muted)
            }
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted)
        if (progress.isEarned) {
            val date = remember(progress.earnedAt) {
                runCatching { dateLabel(progress.earnedAt!!) }.getOrElse { progress.earnedAt.orEmpty() }
            }
            Text("Открыто $date", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Green)
        } else if (hidden) {
            Text("Условие и прогресс откроются вместе с наградой.", style = MaterialTheme.typography.bodySmall, color = Muted)
        } else {
            val shown = progress.current.coerceAtMost(achievement.target)
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$shown / ${achievement.target} ${achievement.unit}", fontSize = 12.sp, color = Green)
                if (achievement.id == AchievementEngine.FOOD_7_DAY_STREAK) {
                    Text("Лучшая серия", fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

/** Small vector icons keep the appearance consistent on phones without emoji fonts. */
@Composable
private fun AchievementBadge(symbol: AchievementSymbol, hidden: Boolean, earned: Boolean) {
    val foreground = if (earned) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val background = if (earned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = background, shape = RoundedCornerShape(18.dp)) {
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            if (hidden) {
                Text("?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = foreground,
                    modifier = Modifier.semantics { contentDescription = "Скрытая награда" })
            } else Canvas(Modifier.size(32.dp)) {
                val u = size.width / 32f
                val stroke = Stroke(1.8f * u, cap = StrokeCap.Round)
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                    drawLine(foreground, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.8f * u, StrokeCap.Round)
                when (symbol) {
                    AchievementSymbol.WEIGHT -> {
                        drawRoundRect(foreground, Offset(4 * u, 5 * u), Size(24 * u, 23 * u), CornerRadius(5 * u), style = stroke)
                        drawArc(foreground, 190f, 160f, false, Offset(9 * u, 9 * u), Size(14 * u, 12 * u), style = stroke)
                        line(16f, 15f, 20f, 10f)
                    }
                    AchievementSymbol.FOOD -> {
                        drawCircle(foreground, 8.5f * u, Offset(17 * u, 16 * u), style = stroke)
                        line(3f, 5f, 3f, 27f); line(6f, 5f, 6f, 11f); line(1f, 11f, 6f, 11f)
                        line(29f, 5f, 29f, 27f)
                    }
                    AchievementSymbol.CALENDAR -> {
                        drawRoundRect(foreground, Offset(4 * u, 7 * u), Size(24 * u, 22 * u), CornerRadius(4 * u), style = stroke)
                        line(10f, 3f, 10f, 10f); line(22f, 3f, 22f, 10f); line(4f, 13f, 28f, 13f)
                        line(11f, 21f, 15f, 25f); line(15f, 25f, 22f, 18f)
                    }
                    AchievementSymbol.STEPS -> {
                        drawOval(foreground, Offset(5 * u, 4 * u), Size(8 * u, 14 * u), style = stroke)
                        drawCircle(foreground, 3 * u, Offset(9 * u, 24 * u), style = stroke)
                        drawOval(foreground, Offset(20 * u, 10 * u), Size(8 * u, 14 * u), style = stroke)
                        drawCircle(foreground, 3 * u, Offset(24 * u, 4 * u), style = stroke)
                    }
                    AchievementSymbol.NOTEBOOK -> {
                        drawRoundRect(foreground, Offset(6 * u, 4 * u), Size(21 * u, 25 * u), CornerRadius(3 * u), style = stroke)
                        line(11f, 4f, 11f, 29f); line(15f, 11f, 23f, 11f); line(15f, 16f, 23f, 16f); line(15f, 21f, 20f, 21f)
                        listOf(9f, 16f, 23f).forEach { y -> line(3f, y, 8f, y) }
                    }
                }
                if (earned) {
                    val check = Path().apply { moveTo(23 * u, 25 * u); lineTo(26 * u, 28 * u); lineTo(31 * u, 22 * u) }
                    drawCircle(background, 7 * u, Offset(27 * u, 25 * u))
                    drawPath(check, foreground, style = stroke)
                }
            }
        }
    }
}
