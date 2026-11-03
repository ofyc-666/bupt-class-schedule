package com.bupt.schedule.domain.logic

import com.bupt.schedule.domain.model.ScheduleSnapshot
import java.util.Calendar

/** Tracks explicit week browsing independently of clock updates and network refresh. */
class ScheduleDateState {
    var followsCurrentWeek = true
    private var lastDate: String? = null

    fun dateChanged(now: Calendar): Boolean {
        val date = "${now.get(Calendar.YEAR)}-${now.get(Calendar.DAY_OF_YEAR)}"
        val changed = date != lastDate
        lastDate = date
        return changed
    }

    fun selectedWeek(snapshot: ScheduleSnapshot, selected: Int, now: Calendar): Int =
        if (followsCurrentWeek) ScheduleLogic.currentWeek(snapshot, now) ?: selected.coerceIn(1, ScheduleLogic.maxWeek(snapshot))
        else selected.coerceIn(1, ScheduleLogic.maxWeek(snapshot))

    fun select(snapshot: ScheduleSnapshot, week: Int, now: Calendar) {
        followsCurrentWeek = week == ScheduleLogic.currentWeek(snapshot, now)
    }

    companion object {
        fun defaultFirstDay(todayIndex: Int): Int = if (todayIndex in 5..6) 2 else 0
        fun nextMinuteDelay(now: Calendar): Long = 60_000L - now.get(Calendar.SECOND) * 1000L - now.get(Calendar.MILLISECOND)
    }
}
