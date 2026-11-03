package com.bupt.schedule.domain.logic

import org.junit.Assert.*
import org.junit.Test

class ScheduleHorizontalStateTest {
    @Test fun weekendColdEntryMovesToWednesdayOnce() {
        val state = ScheduleHorizontalState()
        state.positionForToday(6, resetUser = true)
        assertEquals(2, state.consumePosition(false))
        state.positionForToday(6)
        assertNull(state.consumePosition(false))
    }
    @Test fun automaticWeekendTransitionRespectsUserDrag() {
        val automatic = ScheduleHorizontalState()
        automatic.positionForToday(4, resetUser = true)
        assertEquals(0, automatic.consumePosition(false))
        automatic.positionForToday(5)
        assertEquals(2, automatic.consumePosition(false))
        val manual = ScheduleHorizontalState()
        manual.onUserDrag()
        manual.positionForToday(5)
        assertNull(manual.consumePosition(false))
        manual.positionForToday(6)
        assertNull(manual.consumePosition(false))
    }
    @Test fun explicitWeekOrTabEntryRestoresDefaultWhileTouchesDelayApplication() {
        val state = ScheduleHorizontalState()
        state.onUserDrag()
        state.positionForToday(5, resetUser = true)
        assertNull(state.consumePosition(true))
        assertEquals(2, state.consumePosition(false))
        state.onUserDrag()
        state.positionForToday(0, resetUser = true)
        assertEquals(0, state.consumePosition(false))
    }
    @Test fun dragCancelsPendingAutomaticMovementDuringMidnight() {
        val state = ScheduleHorizontalState()
        state.positionForToday(5)
        assertNull(state.consumePosition(true))
        state.onUserDrag()
        assertNull(state.consumePosition(false))
    }
}
