package com.bupt.schedule.domain.model

enum class ScheduleFailureKind {
    EMPTY_CREDENTIALS,
    LOGIN_FAILED,
    NETWORK_UNREACHABLE,
    HTTP_FAILED,
    INVALID_RESPONSE,
    MISSING_TOKEN,
    EMPTY_SCHEDULE,
    LOCAL_STORAGE,
}

class ScheduleException(
    val kind: ScheduleFailureKind,
    message: String,
    val retryable: Boolean = false,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
