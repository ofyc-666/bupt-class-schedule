package com.bupt.schedule.domain.model

/** 领域层统一使用 0-based startSlot/endSlot。 */
data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val room: String,
    val weekText: String,
    val weekNumbers: List<Int>,
    val weekday: Int,
    val startSlot: Int,
    val endSlot: Int,
    val sourceCourseID: String?,
    val startTime: String = "",
    val endTime: String = "",
    val teachingClass: String = "",
) {
    init {
        require(weekday in 1..7) { "weekday 必须在 1 到 7 之间" }
        require(startSlot in 0..13 && endSlot in startSlot..13) { "课程节次必须落在 0 到 13" }
    }
}

data class ScheduleSnapshot(
    val termID: String,
    val termStartDate: String,
    val fetchedAt: String,
    val courses: List<Course>,
)
