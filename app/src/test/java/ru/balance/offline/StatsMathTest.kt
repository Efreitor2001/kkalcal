package ru.balance.offline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsMathTest {
    private fun date(day: Int) = LocalDate.of(2026, 10, day)
    private fun entry(id: String, day: Int, grams: Double = 100.0, macros: Macros = Macros(100.0, 10.0, 4.0, 20.0)) =
        Entry(id, date(day).toString(), "Обед", "product", "Продукт", grams, macros)

    @Test fun measurementGapsStayMissingAndRecordedZeroIsKept() {
        val data = AppData(emptyList(), logs = listOf(
            DailyLog(date(3).toString(), steps = 2500),
            DailyLog(date(1).toString(), steps = 0),
            DailyLog(date(2).toString(), weight = 80.0)
        ))
        val points = statsAggregate(data, StatsMetric.STEPS)
        assertEquals(listOf(date(1), date(3)), points.map { it.date })
        assertEquals(listOf(0.0, 2500.0), points.map { it.value })
        assertEquals(1250.0, points.map { it.value }.average(), 0.0001)
    }

    @Test fun sumsFoodForRecordedDatesWithoutFillingCalendarGaps() {
        val data = AppData(emptyList(), entries = listOf(entry("a", 1), entry("b", 1, 50.0), entry("c", 4, 200.0)))
        val points = statsAggregate(data, StatsMetric.KCAL)
        assertEquals(listOf(date(1), date(4)), points.map { it.date })
        assertEquals(listOf(150.0, 200.0), points.map { it.value })
    }

    @Test fun calorieOverrideReplacesFoodAndCanCreateDayWithoutProducts() {
        val data = AppData(emptyList(), entries = listOf(entry("a", 1)), logs = listOf(
            DailyLog(date(1).toString(), calories = 2000.0),
            DailyLog(date(2).toString(), calories = 1600.0),
            DailyLog(date(3).toString(), weight = 80.0)
        ))
        val points = statsAggregate(data, StatsMetric.KCAL)
        assertEquals(listOf(date(1), date(2)), points.map { it.date })
        assertEquals(listOf(2000.0, 1600.0), points.map { it.value })
    }

    @Test fun zeroOverridesAreNotTreatedAsMissing() {
        val data = AppData(emptyList(), entries = listOf(entry("a", 1)),
            logs = listOf(DailyLog(date(1).toString(), calories = 0.0, protein = 0.0)))
        assertEquals(0.0, statsAggregate(data, StatsMetric.KCAL).single().value, 0.0)
        assertEquals(0.0, statsAggregate(data, StatsMetric.PROTEIN).single().value, 0.0)
    }

    @Test fun proteinOverrideDoesNotReplaceOtherNutrientsOrCalories() {
        val data = AppData(emptyList(), entries = listOf(entry("a", 1, 200.0)),
            logs = listOf(DailyLog(date(1).toString(), protein = 90.0)))
        assertEquals(90.0, statsAggregate(data, StatsMetric.PROTEIN).single().value, 0.0)
        assertEquals(200.0, statsAggregate(data, StatsMetric.KCAL).single().value, 0.0)
        assertEquals(8.0, statsAggregate(data, StatsMetric.FAT).single().value, 0.0)
        assertEquals(40.0, statsAggregate(data, StatsMetric.CARBS).single().value, 0.0)
    }

    @Test fun foodWithZeroCaloriesIsStillAnActualRecordedDay() {
        val data = AppData(emptyList(), entries = listOf(entry("water", 1, 250.0, Macros())),
            logs = listOf(DailyLog(date(2).toString(), sleep = 7.0)))
        assertEquals(listOf(StatsPoint(date(1), 0.0)), statsAggregate(data, StatsMetric.KCAL))
    }

    @Test fun bodyFatUsesOnlyBodyFatMeasurements() {
        val data = AppData(emptyList(), logs = listOf(
            DailyLog(date(1).toString(), weight = 80.0),
            DailyLog(date(4).toString(), bodyFat = 23.7)
        ))
        assertEquals(listOf(StatsPoint(date(4), 23.7)), statsAggregate(data, StatsMetric.BODY_FAT))
    }

    @Test fun yesterdayComparisonNeverFallsBackToAnOlderMeasurement() {
        val points = listOf(StatsPoint(date(1), 80.0), StatsPoint(date(3), 79.5))
        val (today, yesterday) = statsTodayAndYesterday(points, date(3))
        assertEquals(79.5, today!!.value, 0.0)
        assertNull(yesterday)
        val (missingToday, actualYesterday) = statsTodayAndYesterday(points, date(4))
        assertNull(missingToday)
        assertEquals(79.5, actualYesterday!!.value, 0.0)
    }

    @Test fun dateRangeIncludesBothBoundsAndRejectsReversedRange() {
        val points = (1..5).map { StatsPoint(date(it), it.toDouble()) }
        assertEquals(listOf(date(2), date(3), date(4)), statsPointsInRange(points, date(2), date(4)).map { it.date })
        assertEquals(listOf(StatsPoint(date(3), 3.0)), statsPointsInRange(points, date(3), date(3)))
        assertTrue(statsPointsInRange(points, date(4), date(2)).isEmpty())
    }

    @Test fun irregularMeasurementsUseElapsedCalendarDaysForChartPosition() {
        assertEquals(0.0, statsDayFraction(date(1), date(1), date(10)), 0.0)
        assertEquals(1.0 / 9.0, statsDayFraction(date(2), date(1), date(10)), 0.00001)
        assertEquals(1.0, statsDayFraction(date(10), date(1), date(10)), 0.0)
        assertEquals(0.5, statsDayFraction(date(5), date(5), date(5)), 0.0)
        val leapStart = LocalDate.of(2024, 2, 28)
        assertEquals(0.5, statsDayFraction(leapStart.plusDays(1), leapStart, leapStart.plusDays(2)), 0.0)
    }

    @Test fun chartAxesContainConstantMeasurementsAndPreserveBarZeroBaseline() {
        val constant = statsAxis(listOf(StatsPoint(date(1), 81.0), StatsPoint(date(2), 81.0)), false)
        assertTrue(constant.low <= 81.0 && constant.high >= 81.0)
        assertTrue(constant.high > constant.low && constant.step > 0.0)
        val zeros = statsAxis(listOf(StatsPoint(date(1), 0.0)), true)
        assertEquals(0.0, zeros.low, 0.0)
        assertTrue(zeros.high > 0.0)
        assertFalse(zeros.step.isNaN())
    }
}
