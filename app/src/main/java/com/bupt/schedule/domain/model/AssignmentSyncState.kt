package com.bupt.schedule.domain.model

enum class AssignmentSyncState {
    IDLE,
    LOADING,
    SUCCESS,
    PARTIAL,
    FAILED_WITH_CACHE,
    FAILED_NO_CACHE,
}
