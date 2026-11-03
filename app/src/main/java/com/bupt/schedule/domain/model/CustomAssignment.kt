package com.bupt.schedule.domain.model

import java.util.UUID

/** Local-only data. A deadline is an instant; display dates use the timetable's Shanghai zone. */
data class CustomAssignment(
    val id: String = "LOCAL:${UUID.randomUUID()}",
    val title: String,
    val courseName: String,
    val deadlineMillis: Long,
    val description: String = "",
) {
    init {
        require(id.startsWith("LOCAL:"))
        require(title.isNotBlank() && courseName.isNotBlank())
        require(deadlineMillis > 0)
    }
}
