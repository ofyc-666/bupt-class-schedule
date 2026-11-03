package com.bupt.schedule.domain.logic

import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.domain.model.ScheduleSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ScheduleDateStateTest {
    private val snapshot = ScheduleSnapshot("2026-2027-1", "2026-09-07", "",
        listOf(Course("demo", "demo", "", "", "", (1..20).toList(), 1, 0, 1, null)))
    private fun date(value: String) = UCloudAssignmentParser.parseDeadlineDate(value)!!

    @Test fun ordinaryMidnightUpdatesTodayWithoutChangingWeek() {
        val state = ScheduleDateState()
        val tuesday = date("2026-10-06 23:59:59")
        val wednesday = date("2026-10-07 00:00:00")
        assertTrue(state.dateChanged(tuesday))
        assertFalse(state.dateChanged(tuesday))
        assertTrue(state.dateChanged(wednesday))
        assertEquals(5, state.selectedWeek(snapshot, 5, wednesday))
        val days = ScheduleLogic.daysOfWeek(ScheduleLogic.mondayOfWeek(snapshot.termStartDate, 5)!!)
        assertEquals(1, ScheduleLogic.todayIndexInWeek(days, tuesday))
        assertEquals(2, ScheduleLogic.todayIndexInWeek(days, wednesday))
    }

    @Test fun sundayToMondayFollowsNewWeekWithNewCoursesAndHighlight() {
        val state = ScheduleDateState()
        val sunday = date("2026-10-11 23:59:59")
        val monday = date("2026-10-12 00:00:00")
        state.dateChanged(sunday)
        assertTrue(state.dateChanged(monday))
        val week = state.selectedWeek(snapshot, 5, monday)
        assertEquals(6, week)
        assertEquals(1, ScheduleLogic.coursesForWeek(snapshot, week).size)
        assertEquals(0, ScheduleLogic.todayIndexInWeek(ScheduleLogic.daysOfWeek(ScheduleLogic.mondayOfWeek(snapshot.termStartDate, week)!!), monday))
        assertEquals(0, ScheduleDateState.defaultFirstDay(0))
    }

    @Test fun manualOtherWeekSurvivesMidnightAndLongBackgroundAbsenceUntilCurrentWeekSelected() {
        val state = ScheduleDateState()
        state.select(snapshot, 3, date("2026-10-11 23:59:59"))
        assertFalse(state.followsCurrentWeek)
        assertEquals(3, state.selectedWeek(snapshot, 3, date("2026-10-12 00:00:00")))
        assertEquals(3, state.selectedWeek(snapshot, 3, date("2026-11-02 12:00:00")))
        state.select(snapshot, 9, date("2026-11-02 12:00:00"))
        assertTrue(state.followsCurrentWeek)
        assertEquals(10, state.selectedWeek(snapshot, 9, date("2026-11-09 12:00:00")))
    }

    @Test fun weekdaysAndWeekendUseFiveDayWindow() {
        for (day in 0..4) assertEquals(0, ScheduleDateState.defaultFirstDay(day))
        for (day in 5..6) assertEquals(2, ScheduleDateState.defaultFirstDay(day))
        assertEquals(0, ScheduleDateState.defaultFirstDay(-1))
    }

    @Test fun longAbsenceClockBackwardsMonthYearAndSemesterEndAreHandledWithoutNetwork() {
        val state = ScheduleDateState()
        assertEquals(9, state.selectedWeek(snapshot, 5, date("2026-11-02 00:00:00")))
        assertEquals(4, state.selectedWeek(snapshot, 9, date("2026-10-01 00:00:00")))
        assertTrue(state.dateChanged(date("2026-12-31 23:59:59")))
        assertTrue(state.dateChanged(date("2027-01-01 00:00:00")))
        assertEquals(17, state.selectedWeek(snapshot, 17, date("2027-01-01 00:00:00")))
        assertEquals(20, state.selectedWeek(snapshot, 20, date("2027-04-01 00:00:00")))
    }

    @Test fun minuteTickIsAlignedToRealMinuteIncludingMidnight() {
        assertEquals(1000L, ScheduleDateState.nextMinuteDelay(date("2026-10-11 23:59:59")))
        assertEquals(60000L, ScheduleDateState.nextMinuteDelay(date("2026-10-12 00:00:00")))
        val now = date("2026-10-12 00:00:00").apply { set(Calendar.MILLISECOND, 250) }
        assertEquals(59750L, ScheduleDateState.nextMinuteDelay(now))
    }
}
