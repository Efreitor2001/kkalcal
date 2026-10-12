package ru.dietdiary.offline

import java.time.LocalDate

enum class AchievementKind { REGULAR, PROGRESSIVE, HIDDEN }
enum class AchievementSymbol { WEIGHT, FOOD, CALENDAR, STEPS, NOTEBOOK }

data class AchievementDefinition(
    val id: String,
    private val titleRes: Int,
    private val descriptionRes: Int,
    private val unlockedMessageRes: Int,
    val target: Int,
    private val unitRes: Int,
    val kind: AchievementKind,
    val symbol: AchievementSymbol,
) {
    val title: String get() = tr(titleRes)
    val description: String get() = tr(descriptionRes)
    val unlockedMessage: String get() = tr(unlockedMessageRes)
    val unit: String get() = tr(unitRes)
}

data class AchievementProgress(
    val definition: AchievementDefinition,
    /** Actual count from the currently saved diary, without an artificial minimum after earning. */
    val current: Int,
    val earnedAt: String?,
) {
    val isEarned: Boolean get() = earnedAt != null
    val isHidden: Boolean get() = definition.kind == AchievementKind.HIDDEN && !isEarned
    val fraction: Float get() = (current.toFloat() / definition.target).coerceIn(0f, 1f)
}

/**
 * Pure, deterministic achievement rules. No clock, storage, network or notification side effects.
 * AppStore merges evaluate()'s NEW awards into the existing map before the atomic commit.
 * Keeping that map through later edits and cloud merge makes awards permanent. Sync/import can
 * evaluate and save silently; only a successful local mutation should enqueue a notification.
 */
object AchievementEngine {
    const val FIRST_WEIGHT = "first_weight"
    const val FIRST_FOOD = "first_food"
    const val WEIGHT_7_DAYS = "weight_7_days"
    const val WEIGHT_30_DAYS = "weight_30_days"
    const val FOOD_100_ENTRIES = "food_100_entries"
    const val FOOD_7_DAY_STREAK = "food_7_day_streak"
    const val STEPS_10000 = "steps_10000"
    const val WEIGHT_100_MEASUREMENTS = "weight_100_measurements"
    const val DIARY_90_DAYS = "diary_90_days"

    val definitions: List<AchievementDefinition> = listOf(
        AchievementDefinition(FIRST_WEIGHT, R.string.secondary_award_first_weight, R.string.secondary_award_first_weight_description,
            R.string.secondary_award_first_weight_unlocked, 1, R.string.secondary_award_unit_measurements, AchievementKind.REGULAR, AchievementSymbol.WEIGHT),
        AchievementDefinition(FIRST_FOOD, R.string.secondary_award_first_food, R.string.secondary_award_first_food_description,
            R.string.secondary_award_first_food_unlocked, 1, R.string.secondary_award_unit_portions, AchievementKind.REGULAR, AchievementSymbol.FOOD),
        AchievementDefinition(WEIGHT_7_DAYS, R.string.secondary_award_weight_week, R.string.secondary_award_weight_week_description,
            R.string.secondary_award_weight_week_unlocked, 7, R.string.secondary_award_unit_days, AchievementKind.PROGRESSIVE, AchievementSymbol.CALENDAR),
        AchievementDefinition(WEIGHT_30_DAYS, R.string.secondary_award_weight_month, R.string.secondary_award_weight_month_description,
            R.string.secondary_award_weight_month_unlocked, 30, R.string.secondary_award_unit_days, AchievementKind.PROGRESSIVE, AchievementSymbol.WEIGHT),
        AchievementDefinition(FOOD_100_ENTRIES, R.string.secondary_award_food_hundred, R.string.secondary_award_food_hundred_description,
            R.string.secondary_award_food_hundred_unlocked, 100, R.string.secondary_award_unit_portions, AchievementKind.PROGRESSIVE, AchievementSymbol.NOTEBOOK),
        AchievementDefinition(FOOD_7_DAY_STREAK, R.string.secondary_award_food_streak, R.string.secondary_award_food_streak_description,
            R.string.secondary_award_food_streak_unlocked, 7, R.string.secondary_award_unit_consecutive, AchievementKind.PROGRESSIVE, AchievementSymbol.CALENDAR),
        AchievementDefinition(STEPS_10000, R.string.secondary_award_steps, R.string.secondary_award_steps_description,
            R.string.secondary_award_steps_unlocked, 10_000, R.string.secondary_award_unit_steps, AchievementKind.REGULAR, AchievementSymbol.STEPS),
        AchievementDefinition(WEIGHT_100_MEASUREMENTS, R.string.secondary_award_weight_hundred, R.string.secondary_award_weight_hundred_description,
            R.string.secondary_award_weight_hundred_unlocked, 100, R.string.secondary_award_unit_measurements, AchievementKind.HIDDEN, AchievementSymbol.NOTEBOOK),
        AchievementDefinition(DIARY_90_DAYS, R.string.secondary_award_diary_ninety, R.string.secondary_award_diary_ninety_description,
            R.string.secondary_award_diary_ninety_unlocked, 90, R.string.secondary_award_unit_recorded_days, AchievementKind.PROGRESSIVE, AchievementSymbol.NOTEBOOK),
    )

    fun find(id: String): AchievementDefinition? = definitions.firstOrNull { it.id == id }

    /** Only newly earned IDs are returned; caller retains all previously persisted awards. */
    fun evaluate(data: AppData, today: LocalDate): Map<String, String> =
        progress(data, today).filter { !it.isEarned && it.current >= it.definition.target }
            .associate { it.definition.id to today.toString() }

    fun progress(data: AppData, today: LocalDate): List<AchievementProgress> {
        val entries = data.entries.asSequence()
            .filter { it.id.isNotBlank() && it.grams.isFinite() && it.grams > 0.0 }
            .mapNotNull { entry -> recordedDate(entry.date, today)?.let { it to entry } }
            .distinctBy { it.second.id }
            .toList()
        val logs = data.logs.mapNotNull { log -> recordedDate(log.date, today)?.let { it to log } }
        val weightDates = logs.filter { (_, log) -> log.weight.isIn(1.0, 500.0) }.map { it.first }.toSet()
        val foodDates = entries.map { it.first }.toSet()
        val activityDates = foodDates + logs.filter { (_, log) -> hasObservation(log) }.map { it.first }
        val bestSteps = logs.mapNotNull { (_, log) -> log.steps?.takeIf { it in 0..200_000 } }.maxOrNull() ?: 0
        val counts = mapOf(
            FIRST_WEIGHT to weightDates.size,
            FIRST_FOOD to entries.size,
            WEIGHT_7_DAYS to weightDates.size,
            WEIGHT_30_DAYS to weightDates.size,
            FOOD_100_ENTRIES to entries.size,
            FOOD_7_DAY_STREAK to longestSequence(foodDates),
            STEPS_10000 to bestSteps,
            WEIGHT_100_MEASUREMENTS to weightDates.size,
            DIARY_90_DAYS to activityDates.size,
        )
        return definitions.map { definition ->
            AchievementProgress(definition, counts.getValue(definition.id), data.achievements[definition.id])
        }
    }

    private fun recordedDate(value: String, today: LocalDate): LocalDate? =
        runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { !it.isAfter(today) }

    private fun longestSequence(dates: Set<LocalDate>): Int {
        var previous: LocalDate? = null
        var current = 0
        var longest = 0
        dates.sorted().forEach { date ->
            current = if (previous != null && date.toEpochDay() - previous!!.toEpochDay() == 1L) current + 1 else 1
            longest = maxOf(longest, current)
            previous = date
        }
        return longest
    }

    private fun Double?.isIn(min: Double, max: Double): Boolean =
        this != null && isFinite() && this in min..max

    private fun hasObservation(log: DailyLog): Boolean =
        log.weight.isIn(1.0, 500.0) || log.waist.isIn(20.0, 400.0) ||
            log.steps?.let { it in 0..200_000 } == true || log.sleep.isIn(0.0, 24.0) ||
            log.bodyFat.isIn(0.0, 100.0) || log.calories.isIn(0.0, 100_000.0) ||
            log.protein.isIn(0.0, 10_000.0) || log.training.isNotBlank() || log.note.isNotBlank()
}
