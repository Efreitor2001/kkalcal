package ru.dietdiary.offline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {
    private val today = LocalDate.of(2026, 10, 9)
    private fun food(id: String, date: LocalDate, kcal: Double = 200.0) =
        Entry(id, date.toString(), "Обед", "product", "Еда", 100.0, Macros(kcal, 10.0, 8.0, 22.0))
    private fun weight(date: LocalDate) = DailyLog(date.toString(), weight = 80.0)
    private fun count(data: AppData, id: String): Int =
        AchievementEngine.progress(data, today).single { it.definition.id == id }.current

    @Test fun blankDiaryAndCatalogEditsCannotEarnAchievements() {
        val data = AppData(listOf(Product("my_product", "Продукт", "Мои продукты", Macros(100.0))))
        assertTrue(AchievementEngine.evaluate(data, today).isEmpty())
        assertEquals(9, AchievementEngine.progress(data, today).size)
        assertTrue(AchievementEngine.progress(data, today).all { it.current == 0 && !it.isEarned })
        assertNull(AchievementEngine.find("unknown"))
    }

    @Test fun firstFoodAndWeightAwardOnceWithTheEvaluationDate() {
        val data = AppData(emptyList(), entries = listOf(food("food", today.minusDays(2))),
            logs = listOf(weight(today.minusDays(1))))
        val awards = AchievementEngine.evaluate(data, today)
        assertEquals(mapOf(AchievementEngine.FIRST_WEIGHT to today.toString(),
            AchievementEngine.FIRST_FOOD to today.toString()), awards)
        assertTrue(AchievementEngine.evaluate(data.copy(achievements = awards), today.plusDays(1)).isEmpty())
    }

    @Test fun weightMilestonesUseDifferentDatesAndDoNotRequireConsecutiveDays() {
        val logs = (0 until 30).map { weight(today.minusDays(it * 2L)) }
        val six = AppData(emptyList(), logs = logs.take(6) + logs.take(6))
        assertEquals(6, count(six, AchievementEngine.WEIGHT_7_DAYS))
        assertFalse(AchievementEngine.evaluate(six, today).containsKey(AchievementEngine.WEIGHT_7_DAYS))
        val seven = six.copy(logs = logs.take(7))
        assertTrue(AchievementEngine.evaluate(seven, today).containsKey(AchievementEngine.WEIGHT_7_DAYS))
        val thirty = six.copy(logs = logs)
        assertTrue(AchievementEngine.evaluate(thirty, today).containsKey(AchievementEngine.WEIGHT_30_DAYS))
        assertEquals(30, count(thirty, AchievementEngine.WEIGHT_100_MEASUREMENTS))
    }

    @Test fun oneHundredFoodEntriesCountIdsInsteadOfMealsOrDays() {
        val entries = (1..100).map { food("food_$it", today) }
        val ninetyNine = AppData(emptyList(), entries = entries.take(99) + entries.first())
        assertEquals(99, count(ninetyNine, AchievementEngine.FOOD_100_ENTRIES))
        assertFalse(AchievementEngine.evaluate(ninetyNine, today).containsKey(AchievementEngine.FOOD_100_ENTRIES))
        assertTrue(AchievementEngine.evaluate(ninetyNine.copy(entries = entries), today)
            .containsKey(AchievementEngine.FOOD_100_ENTRIES))
        assertEquals(1, count(ninetyNine.copy(entries = entries), AchievementEngine.DIARY_90_DAYS))
    }

    @Test fun foodStreakBreaksAtAMissingCalendarDayAndIgnoresDuplicatePortions() {
        val start = today.minusDays(8)
        val days = listOf(0L, 1L, 2L, 4L, 5L, 6L, 7L)
        val entries = days.map { food("day_$it", start.plusDays(it)) }
        val gap = AppData(emptyList(), entries = entries + food("extra", start.plusDays(4)))
        assertEquals(4, count(gap, AchievementEngine.FOOD_7_DAY_STREAK))
        assertFalse(AchievementEngine.evaluate(gap, today).containsKey(AchievementEngine.FOOD_7_DAY_STREAK))
        val filled = gap.copy(entries = gap.entries + food("filled", start.plusDays(3)))
        assertEquals(8, count(filled, AchievementEngine.FOOD_7_DAY_STREAK))
        assertTrue(AchievementEngine.evaluate(filled, today).containsKey(AchievementEngine.FOOD_7_DAY_STREAK))
    }

    @Test fun historicalStreakCrossesMonthAndLeapDayWithoutNeedingAnEntryToday() {
        val start = LocalDate.of(2024, 2, 26)
        val data = AppData(emptyList(), entries = (0..6).map { food("day_$it", start.plusDays(it.toLong())) })
        assertEquals(7, count(data, AchievementEngine.FOOD_7_DAY_STREAK))
        assertTrue(AchievementEngine.evaluate(data, today).containsKey(AchievementEngine.FOOD_7_DAY_STREAK))
    }

    @Test fun stepAchievementRequiresTenThousandOnOneDayInsteadOfSummingDays() {
        val data = AppData(emptyList(), logs = listOf(
            DailyLog(today.minusDays(1).toString(), steps = 6_000),
            DailyLog(today.toString(), steps = 9_999)))
        assertEquals(9_999, count(data, AchievementEngine.STEPS_10000))
        assertFalse(AchievementEngine.evaluate(data, today).containsKey(AchievementEngine.STEPS_10000))
        assertTrue(AchievementEngine.evaluate(data.copy(logs = listOf(DailyLog(today.toString(), steps = 10_000))), today)
            .containsKey(AchievementEngine.STEPS_10000))
    }

    @Test fun veteranRequiresNinetyRealDistinctDatesAndAllowsOnlyOneDailyIndicator() {
        val singleOldRecord = AppData(emptyList(), logs = listOf(DailyLog(today.minusDays(200).toString(), steps = 0)))
        assertEquals(1, count(singleOldRecord, AchievementEngine.DIARY_90_DAYS))
        assertTrue(AchievementEngine.evaluate(singleOldRecord, today).isEmpty())
        val logs = (0..88).map { DailyLog(today.minusDays(it.toLong()).toString(), note = "Запись") }
        val data = AppData(emptyList(), logs = logs, entries = listOf(food("overlap", today)))
        assertEquals(89, count(data, AchievementEngine.DIARY_90_DAYS))
        assertFalse(AchievementEngine.evaluate(data, today).containsKey(AchievementEngine.DIARY_90_DAYS))
        val ninety = data.copy(entries = data.entries + food("older", today.minusDays(89)))
        assertEquals(90, count(ninety, AchievementEngine.DIARY_90_DAYS))
        assertTrue(AchievementEngine.evaluate(ninety, today).containsKey(AchievementEngine.DIARY_90_DAYS))
    }

    @Test fun futureDatesDoNotAwardAnyMilestoneOrIncreaseProgress() {
        val future = AppData(emptyList(),
            entries = (1..100).map { food("future_$it", today.plusDays(it.toLong())) },
            logs = (1..100).map { DailyLog(today.plusDays(it.toLong()).toString(), weight = 80.0, steps = 15_000) })
        assertTrue(AchievementEngine.evaluate(future, today).isEmpty())
        assertTrue(AchievementEngine.progress(future, today).all { it.current == 0 })
    }

    @Test fun emptyOrMalformedRecordsDoNotCreateUsageDaysButZeroCaloriesFoodDoes() {
        val malformed = AppData(emptyList(),
            entries = listOf(food("invalid_date", today).copy(date = "not-a-date"),
                food("invalid_weight", today).copy(grams = Double.NaN)),
            logs = listOf(DailyLog(today.toString()), DailyLog(today.minusDays(1).toString(), weight = Double.NaN)))
        assertTrue(AchievementEngine.evaluate(malformed, today).isEmpty())
        assertEquals(0, count(malformed, AchievementEngine.DIARY_90_DAYS))
        val recordedFood = malformed.copy(entries = listOf(food("water", today, kcal = 0.0)))
        assertTrue(AchievementEngine.evaluate(recordedFood, today).containsKey(AchievementEngine.FIRST_FOOD))
        assertEquals(1, count(recordedFood, AchievementEngine.DIARY_90_DAYS))
    }

    @Test fun hiddenAchievementUnlocksAtOneHundredWeightDatesAndStaysEarnedAfterDeletion() {
        val data = AppData(emptyList(), logs = (0..98).map { weight(today.minusDays(it.toLong())) })
        val locked = AchievementEngine.progress(data, today).single { it.definition.id == AchievementEngine.WEIGHT_100_MEASUREMENTS }
        assertTrue(locked.isHidden)
        assertEquals(99, locked.current)
        val hundred = data.copy(logs = data.logs + weight(today.minusDays(99)))
        val awards = AchievementEngine.evaluate(hundred, today)
        assertTrue(awards.containsKey(AchievementEngine.WEIGHT_100_MEASUREMENTS))
        val deleted = AppData(emptyList(), achievements = awards)
        assertTrue(AchievementEngine.evaluate(deleted, today.plusDays(3)).isEmpty())
        assertEquals(awards, deleted.achievements)
        val unlocked = AchievementEngine.progress(deleted, today).single { it.definition.id == AchievementEngine.WEIGHT_100_MEASUREMENTS }
        assertTrue(unlocked.isEarned)
        assertFalse(unlocked.isHidden)
        assertEquals(today.toString(), unlocked.earnedAt)
        assertEquals(0, unlocked.current)
    }

    @Test fun importedAwardsAreNotReissuedAndTheirDatesAndUnknownIdsArePreserved() {
        val originalDate = today.minusDays(40).toString()
        val saved = mapOf(AchievementEngine.FIRST_WEIGHT to originalDate, "future_award_type" to originalDate)
        val data = AppData(emptyList(), logs = listOf(weight(today)), achievements = saved)
        assertTrue(AchievementEngine.evaluate(data, today).isEmpty())
        assertEquals(saved, data.achievements)
        assertEquals(originalDate, AchievementEngine.progress(data, today).first { it.definition.id == AchievementEngine.FIRST_WEIGHT }.earnedAt)
    }
}
