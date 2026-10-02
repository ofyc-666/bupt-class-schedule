package com.bupt.schedule.domain.logic

import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.domain.model.ScheduleSnapshot
import com.bupt.schedule.ui.ScheduleUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleLogicTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun defaultSlotTimesCoverAll14PeriodsWithCorrectAfternoonTimes() {
        assertEquals(14, ScheduleLogic.DEFAULT_SLOT_TIMES.size)
        // Period 8 (slot 7)
        assertEquals("14:45" to "15:30", ScheduleLogic.defaultSlotTime(7))
        // Period 9 (slot 8)
        assertEquals("15:40" to "16:25", ScheduleLogic.defaultSlotTime(8))
        // Period 10 (slot 9)
        assertEquals("16:35" to "17:20", ScheduleLogic.defaultSlotTime(9))
        // Period 11 (slot 10)
        assertEquals("17:25" to "18:10", ScheduleLogic.defaultSlotTime(10))

        assertEquals(null, ScheduleLogic.defaultSlotTime(-1))
        assertEquals(null, ScheduleLogic.defaultSlotTime(14))
    }

    @Test
    fun formatTeachingClassCleansOuterBracketsAndNormalizesRange() {
        assertEquals("DEMO01–DEMO05 班", ScheduleLogic.formatTeachingClass("[DEMO01-DEMO05]班"))
        assertEquals("DEMO01–DEMO05 班", ScheduleLogic.formatTeachingClass("[DEMO01–DEMO05]班"))
        assertEquals("DEMO01 班", ScheduleLogic.formatTeachingClass("[DEMO01]班"))
        assertEquals("DEMO01–DEMO05", ScheduleLogic.formatTeachingClass("[DEMO01-DEMO05]"))
        assertEquals("DEMO01班", ScheduleLogic.formatTeachingClass("DEMO01班"))
        assertEquals("暂无教学班信息", ScheduleLogic.formatTeachingClass(""))
        assertEquals("暂无教学班信息", ScheduleLogic.formatTeachingClass("   "))
    }

    @Test
    fun defaultSlotTimeRangeCoversStartAndEndSlots() {
        val range = ScheduleLogic.defaultSlotTimeRange(0, 5)
        assertEquals("08:00", range?.first)
        assertEquals("13:45", range?.second)

        val single = ScheduleLogic.defaultSlotTimeRange(2, 4)
        assertEquals("09:50", single?.first)
        assertEquals("12:15", single?.second)

        assertEquals(null, ScheduleLogic.defaultSlotTimeRange(-1, 0))
        assertEquals(null, ScheduleLogic.defaultSlotTimeRange(0, 99))
    }

    @Test
    fun calculatesCurrentWeekAndFiltersWithoutExpandingMatrix() {
        val snapshot = schedule(
            course("week-1", listOf(1), weekday = 1, start = 0, end = 1),
            course("week-2", listOf(2), weekday = 3, start = 2, end = 4),
        )
        val target = date(2026, Calendar.MARCH, 11)

        assertEquals(2, ScheduleLogic.currentWeek(snapshot, target))
        assertEquals(listOf("week-2"), ScheduleLogic.coursesForWeek(snapshot, 2).map(Course::id))
    }

    @Test
    fun adapterOwnsZeroBasedToOneBasedConversion() {
        val item = ScheduleUiMapper.map(
            listOf(course("course", listOf(1), weekday = 3, start = 2, end = 4)),
        ).single()

        assertEquals(3, item.day)
        assertEquals(3, item.startPeriod)
        assertEquals(3, item.duration)
    }

    @Test
    fun dateBeforeFirstMondayIsNotTeachingWeek() {
        val snapshot = schedule(course("course", listOf(1), 1, 0, 1))
        assertEquals(null, ScheduleLogic.currentWeek(snapshot, date(2026, Calendar.MARCH, 1)))
    }

    @Test
    fun calculatesDatesForWeek1() {
        val monday = ScheduleLogic.mondayOfWeek("2026-03-02", 1)!!
        assertEquals(2026, monday.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, monday.get(Calendar.MONTH))
        assertEquals(2, monday.get(Calendar.DAY_OF_MONTH))

        val days = ScheduleLogic.daysOfWeek(monday)
        assertEquals(7, days.size)
        assertEquals(2, days.first().get(Calendar.DAY_OF_MONTH))
        assertEquals(8, days.last().get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun calculatesDatesForWeekN() {
        val monday = ScheduleLogic.mondayOfWeek("2026-03-02", 3)!!
        assertEquals(2026, monday.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, monday.get(Calendar.MONTH))
        assertEquals(16, monday.get(Calendar.DAY_OF_MONTH))

        val days = ScheduleLogic.daysOfWeek(monday)
        assertEquals(7, days.size)
        assertEquals(16, days.first().get(Calendar.DAY_OF_MONTH))
        assertEquals(22, days.last().get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun filtersCoursesBySelectedWeekNumbers() {
        val c1 = course("c1", listOf(1, 2, 3), weekday = 1, start = 0, end = 1)
        val c2 = course("c2", listOf(2, 4), weekday = 2, start = 2, end = 3)
        val snapshot = schedule(c1, c2)

        assertEquals(listOf("c1"), ScheduleLogic.coursesForWeek(snapshot, 1).map(Course::id))
        assertEquals(listOf("c1", "c2"), ScheduleLogic.coursesForWeek(snapshot, 2).map(Course::id))
        assertEquals(listOf("c1"), ScheduleLogic.coursesForWeek(snapshot, 3).map(Course::id))
        assertEquals(listOf("c2"), ScheduleLogic.coursesForWeek(snapshot, 4).map(Course::id))
        assertEquals(emptyList<String>(), ScheduleLogic.coursesForWeek(snapshot, 5).map(Course::id))
    }

    @Test
    fun highlightsTodayOnlyWhenSelectedWeekContainsToday() {
        val today = date(2026, Calendar.MARCH, 11) // Wednesday in week 2

        val week1Days = ScheduleLogic.daysOfWeek(ScheduleLogic.mondayOfWeek("2026-03-02", 1)!!)
        val week2Days = ScheduleLogic.daysOfWeek(ScheduleLogic.mondayOfWeek("2026-03-02", 2)!!)
        val week3Days = ScheduleLogic.daysOfWeek(ScheduleLogic.mondayOfWeek("2026-03-02", 3)!!)

        assertEquals(-1, ScheduleLogic.todayIndexInWeek(week1Days, today))
        assertEquals(2, ScheduleLogic.todayIndexInWeek(week2Days, today))
        assertEquals(-1, ScheduleLogic.todayIndexInWeek(week3Days, today))
    }

    @Test
    fun retainsValidSelectedWeekAfterRefresh() {
        val snapshot = schedule(
            course("c1", listOf(1, 8), weekday = 1, start = 0, end = 1),
            course("c2", listOf(16), weekday = 2, start = 2, end = 3),
        )
        val today = date(2026, Calendar.MARCH, 11) // Week 2

        val resolved = ScheduleLogic.resolveSelectedWeek(previousWeek = 5, snapshot, today)
        assertEquals(5, resolved)

        val firstLaunch = ScheduleLogic.resolveSelectedWeek(previousWeek = null, snapshot, today)
        assertEquals(2, firstLaunch)
    }

    @Test
    fun fallbacksWhenSelectedWeekBecomesInvalid() {
        val snapshot = schedule(
            course("c1", listOf(1, 6), weekday = 1, start = 0, end = 1),
        )
        val today = date(2026, Calendar.MARCH, 11) // Week 2

        val fallbackToCurrent = ScheduleLogic.resolveSelectedWeek(previousWeek = 10, snapshot, today)
        assertEquals(2, fallbackToCurrent)

        val beforeTerm = date(2026, Calendar.JANUARY, 1)
        val fallbackToFirst = ScheduleLogic.resolveSelectedWeek(previousWeek = 10, snapshot, beforeTerm)
        assertEquals(1, fallbackToFirst)
    }

    @Test
    fun initialFetchWithoutExistingSnapshotDefaultsToCurrentWeekEvenIfSelectedWeekIs1() {
        val snapshot = schedule(
            course("c1", listOf(1, 8), weekday = 1, start = 0, end = 1),
        )
        val today = date(2026, Calendar.MARCH, 11) // Week 2

        val resolved = ScheduleLogic.resolveRefreshWeek(
            hasExistingSnapshot = false,
            currentSelectedWeek = 1,
            newSnapshot = snapshot,
            now = today,
        )
        assertEquals(2, resolved)
    }

    @Test
    fun subsequentRefreshWithExistingSnapshotRetainsSelectedWeek() {
        val snapshot = schedule(
            course("c1", listOf(1, 8), weekday = 1, start = 0, end = 1),
        )
        val today = date(2026, Calendar.MARCH, 11) // Week 2

        // User was looking at week 1 explicitly
        val resolvedWeek1 = ScheduleLogic.resolveRefreshWeek(
            hasExistingSnapshot = true,
            currentSelectedWeek = 1,
            newSnapshot = snapshot,
            now = today,
        )
        assertEquals(1, resolvedWeek1)

        // User was looking at week 5 explicitly
        val resolvedWeek5 = ScheduleLogic.resolveRefreshWeek(
            hasExistingSnapshot = true,
            currentSelectedWeek = 5,
            newSnapshot = snapshot,
            now = today,
        )
        assertEquals(5, resolvedWeek5)
    }

    @Test
    fun snapshotsWithOnlyFetchedAtDifferentHaveSameBusinessContent() {
        val s1 = schedule(course("c1", listOf(1), 1, 0, 1)).copy(fetchedAt = "fetch-1")
        val s2 = schedule(course("c1", listOf(1), 1, 0, 1)).copy(fetchedAt = "fetch-2")
        assertTrue(ScheduleLogic.hasSameBusinessContent(s1, s2))
    }

    @Test
    fun snapshotsWithDifferentCoursesHaveDifferentBusinessContent() {
        val s1 = schedule(course("c1", listOf(1), 1, 0, 1))
        val s2 = schedule(course("c2", listOf(1), 1, 0, 1))
        assertFalse(ScheduleLogic.hasSameBusinessContent(s1, s2))
    }

    @Test
    fun snapshotsWithDifferentTermIdOrStartDateHaveDifferentBusinessContent() {
        val s1 = schedule().copy(termID = "2025-2026-2", termStartDate = "2026-03-02")
        val s2 = schedule().copy(termID = "2026-2027-1", termStartDate = "2026-03-02")
        val s3 = schedule().copy(termID = "2025-2026-2", termStartDate = "2026-09-01")
        assertFalse(ScheduleLogic.hasSameBusinessContent(s1, s2))
        assertFalse(ScheduleLogic.hasSameBusinessContent(s1, s3))
    }

    @Test
    fun snapshotsWithReorderedCoursesHaveSameBusinessContent() {
        val c1 = course("c1", listOf(1), 1, 0, 1)
        val c2 = course("c2", listOf(2), 2, 2, 3)
        val s1 = schedule(c1, c2)
        val s2 = schedule(c2, c1)
        assertTrue(ScheduleLogic.hasSameBusinessContent(s1, s2))
    }

    @Test
    fun formatRefreshTimeWithinOneMinute() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 20, 30)
        val refreshTime = now.timeInMillis - 20_000L
        assertEquals("\u521a\u521a\u66f4\u65b0", ScheduleLogic.formatRefreshTime(refreshTime, now))
    }

    @Test
    fun formatRefreshTimeWithinOneHour() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 20, 30)
        val refreshTime = now.timeInMillis - 5 * 60_000L
        assertEquals("5\u5206\u949f\u524d", ScheduleLogic.formatRefreshTime(refreshTime, now))
    }

    @Test
    fun formatRefreshTimeToday() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 21, 0)
        val refreshTime = date(2026, Calendar.SEPTEMBER, 13, 19, 42).timeInMillis
        assertEquals("\u4eca\u5929 19:42", ScheduleLogic.formatRefreshTime(refreshTime, now))
    }

    @Test
    fun formatRefreshTimeEarlierDay() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 21, 0)
        val refreshTime = date(2026, Calendar.SEPTEMBER, 10, 8, 30).timeInMillis
        assertEquals("9\u670810\u65e5 08:30", ScheduleLogic.formatRefreshTime(refreshTime, now))
    }

    @Test
    fun autoRefreshNeededWhenNoPreviousRefreshTime() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 8, 0)
        assertTrue(ScheduleLogic.shouldAutoRefreshToday(null, now))
        assertTrue(ScheduleLogic.shouldAutoRefreshToday(0L, now))
    }

    @Test
    fun autoRefreshSkippedWhenRefreshedEarlierToday() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 14, 0)
        val refreshedEarlierToday = date(2026, Calendar.SEPTEMBER, 13, 8, 30).timeInMillis
        assertFalse(ScheduleLogic.shouldAutoRefreshToday(refreshedEarlierToday, now))
    }

    @Test
    fun autoRefreshNeededWhenRefreshedYesterdayLateNight() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 8, 0)
        val refreshedYesterday = date(2026, Calendar.SEPTEMBER, 12, 23, 50).timeInMillis
        assertTrue(ScheduleLogic.shouldAutoRefreshToday(refreshedYesterday, now))
    }

    @Test
    fun autoRefreshSkippedOnSameDayAcrossManyHours() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 23, 50)
        val refreshedMorning = date(2026, Calendar.SEPTEMBER, 13, 6, 10).timeInMillis
        assertFalse(ScheduleLogic.shouldAutoRefreshToday(refreshedMorning, now))
    }

    @Test
    fun mustFetchOnLaunchWithoutCacheEvenIfRefreshedToday() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 14, 0)
        val refreshedEarlierToday = date(2026, Calendar.SEPTEMBER, 13, 8, 30).timeInMillis

        // Without cache, must fetch regardless of refresh timestamp
        assertTrue(ScheduleLogic.shouldFetchOnLaunch(hasCache = false, refreshedEarlierToday, now))

        // With cache, skips fetch if already refreshed today
        assertFalse(ScheduleLogic.shouldFetchOnLaunch(hasCache = true, refreshedEarlierToday, now))
    }

    @Test
    fun autoRefreshOnForegroundDecisions() {
        val now = date(2026, Calendar.SEPTEMBER, 13, 8, 0)
        val refreshedYesterday = date(2026, Calendar.SEPTEMBER, 12, 23, 50).timeInMillis
        val refreshedToday = date(2026, Calendar.SEPTEMBER, 13, 7, 0).timeInMillis

        // Should trigger when all conditions met and not refreshed today
        assertTrue(
            ScheduleLogic.shouldAutoRefreshOnForeground(
                isScheduleVisible = true,
                hasSnapshot = true,
                hasValidCredentials = true,
                isRefreshInFlight = false,
                lastSuccessfulRefreshAt = refreshedYesterday,
                now = now,
            )
        )

        // Skipped if already refreshed today
        assertFalse(
            ScheduleLogic.shouldAutoRefreshOnForeground(
                isScheduleVisible = true,
                hasSnapshot = true,
                hasValidCredentials = true,
                isRefreshInFlight = false,
                lastSuccessfulRefreshAt = refreshedToday,
                now = now,
            )
        )

        // Skipped if schedule is not visible
        assertFalse(
            ScheduleLogic.shouldAutoRefreshOnForeground(
                isScheduleVisible = false,
                hasSnapshot = true,
                hasValidCredentials = true,
                isRefreshInFlight = false,
                lastSuccessfulRefreshAt = refreshedYesterday,
                now = now,
            )
        )

        // Skipped if no snapshot
        assertFalse(
            ScheduleLogic.shouldAutoRefreshOnForeground(
                isScheduleVisible = true,
                hasSnapshot = false,
                hasValidCredentials = true,
                isRefreshInFlight = false,
                lastSuccessfulRefreshAt = refreshedYesterday,
                now = now,
            )
        )

        // Skipped if no valid credentials
        assertFalse(
            ScheduleLogic.shouldAutoRefreshOnForeground(
                isScheduleVisible = true,
                hasSnapshot = true,
                hasValidCredentials = false,
                isRefreshInFlight = false,
                lastSuccessfulRefreshAt = refreshedYesterday,
                now = now,
            )
        )

        // Skipped if refresh is already in flight (prevents duplicate with bootstrap)
        assertFalse(
            ScheduleLogic.shouldAutoRefreshOnForeground(
                isScheduleVisible = true,
                hasSnapshot = true,
                hasValidCredentials = true,
                isRefreshInFlight = true,
                lastSuccessfulRefreshAt = refreshedYesterday,
                now = now,
            )
        )
    }

    private fun schedule(vararg courses: Course) = ScheduleSnapshot(
        termID = "2025-2026-2",
        termStartDate = "2026-03-02",
        fetchedAt = "fixture",
        courses = courses.toList(),
    )

    private fun course(
        id: String,
        weeks: List<Int>,
        weekday: Int,
        start: Int,
        end: Int,
    ) = Course(id, id, "", "classroom", weeks.joinToString(), weeks, weekday, start, end, id)

    private fun date(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
    ) = Calendar.getInstance(zone).apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }
}
