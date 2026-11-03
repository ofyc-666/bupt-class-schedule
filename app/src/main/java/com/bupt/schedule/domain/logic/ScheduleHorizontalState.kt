package com.bupt.schedule.domain.logic

/** A user drag owns the horizontal position until an explicit week/page entry. */
class ScheduleHorizontalState {
    private var defaultFirstDay = 0
    private var manuallyPositioned = false
    private var pendingFirstDay: Int? = null

    fun positionForToday(todayIndex: Int, resetUser: Boolean = false) {
        val firstDay = ScheduleDateState.defaultFirstDay(todayIndex)
        val changed = firstDay != defaultFirstDay
        defaultFirstDay = firstDay
        if (resetUser) manuallyPositioned = false
        if (resetUser || (changed && !manuallyPositioned)) pendingFirstDay = firstDay
    }

    fun onUserDrag() {
        manuallyPositioned = true
        pendingFirstDay = null
    }

    fun consumePosition(isInteracting: Boolean): Int? {
        if (isInteracting) return null
        return pendingFirstDay.also { pendingFirstDay = null }
    }
}
