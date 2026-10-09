package ru.dietdiary.offline

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

internal enum class StatsMetric(val title: String, val unit: String, val bars: Boolean = false, val food: Boolean = false) {
    WEIGHT("Вес", "кг"), BODY_FAT("Жир тела", "%"), WAIST("Талия", "см"),
    STEPS("Шаги", "шагов", bars = true), SLEEP("Сон", "ч"),
    KCAL("Калории", "ккал", bars = true, food = true),
    PROTEIN("Белки", "г", bars = true, food = true),
    FAT("Жиры", "г", bars = true, food = true), CARBS("Углеводы", "г", bars = true, food = true)
}

internal data class StatsPoint(val date: LocalDate, val value: Double)
internal data class StatsAxis(val low: Double, val high: Double, val step: Double)

/** Missing dates are absent; an explicitly recorded 0 is preserved. Food is summed by date. */
internal fun statsAggregate(data: AppData, metric: StatsMetric): List<StatsPoint> {
    if (metric.food) {
        val entriesByDate = data.entries.groupBy { it.date }
        val logsByDate = data.logs.associateBy { it.date }
        return (entriesByDate.keys + logsByDate.keys).mapNotNull { date ->
            val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
            val manual = when (metric) {
                StatsMetric.KCAL -> logsByDate[date]?.calories
                StatsMetric.PROTEIN -> logsByDate[date]?.protein
                else -> null
            }
            val value = manual ?: entriesByDate[date]?.takeIf { it.isNotEmpty() }?.sumOf { entry ->
                val total = entry.total
                when (metric) {
                    StatsMetric.KCAL -> total.kcal
                    StatsMetric.PROTEIN -> total.protein
                    StatsMetric.FAT -> total.fat
                    StatsMetric.CARBS -> total.carbs
                    else -> 0.0
                }
            }
            value?.takeIf { it.isFinite() }?.let { StatsPoint(parsed, it) }
        }.sortedBy { it.date }
    }
    return data.logs.mapNotNull { log ->
        val parsed = runCatching { LocalDate.parse(log.date) }.getOrNull() ?: return@mapNotNull null
        val value = when (metric) {
            StatsMetric.WEIGHT -> log.weight
            StatsMetric.BODY_FAT -> log.bodyFat
            StatsMetric.WAIST -> log.waist
            StatsMetric.STEPS -> log.steps?.toDouble()
            StatsMetric.SLEEP -> log.sleep
            else -> null
        }
        value?.takeIf { it.isFinite() }?.let { StatsPoint(parsed, it) }
    }.distinctBy { it.date }.sortedBy { it.date }
}

internal fun statsPointsInRange(points: List<StatsPoint>, start: LocalDate, end: LocalDate): List<StatsPoint> =
    points.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }

internal fun statsTodayAndYesterday(points: List<StatsPoint>, today: LocalDate): Pair<StatsPoint?, StatsPoint?> =
    points.firstOrNull { it.date == today } to points.firstOrNull { it.date == today.minusDays(1) }

/** Calendar distance, not position in the list. A one-day interval is centered. */
internal fun statsDayFraction(date: LocalDate, start: LocalDate, end: LocalDate): Double {
    require(!end.isBefore(start)) { "Неверный период" }
    if (start == end) return 0.5
    return ((date.toEpochDay() - start.toEpochDay()).toDouble() / (end.toEpochDay() - start.toEpochDay())).coerceIn(0.0, 1.0)
}

internal fun statsAxis(points: List<StatsPoint>, startAtZero: Boolean): StatsAxis {
    require(points.isNotEmpty())
    val lowest = points.minOf { it.value }
    val highest = points.maxOf { it.value }
    val padding = max((highest - lowest) * 0.15, max(highest * 0.015, 0.5))
    val rawLow = if (startAtZero) 0.0 else (lowest - padding).coerceAtLeast(0.0)
    val rawHigh = (highest + padding).coerceAtLeast(rawLow + 1.0)
    val rough = (rawHigh - rawLow) / 4.0
    val magnitude = 10.0.pow(floor(log10(rough)))
    val normalized = rough / magnitude
    val step = (when { normalized <= 1.0 -> 1.0; normalized <= 2.0 -> 2.0; normalized <= 5.0 -> 5.0; else -> 10.0 }) * magnitude
    val low = if (startAtZero) 0.0 else floor(rawLow / step) * step
    val high = ceil(rawHigh / step) * step
    return StatsAxis(low, high.coerceAtLeast(low + step), step)
}
