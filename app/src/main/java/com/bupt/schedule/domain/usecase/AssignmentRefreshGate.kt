package com.bupt.schedule.domain.usecase

import java.util.concurrent.atomic.AtomicBoolean

/** A single entry gate shared by automatic and manual task refreshes. */
class AssignmentRefreshGate {
    private val busy = AtomicBoolean(false)
    fun begin(): Boolean = busy.compareAndSet(false, true)
    fun end() { busy.set(false) }
}
