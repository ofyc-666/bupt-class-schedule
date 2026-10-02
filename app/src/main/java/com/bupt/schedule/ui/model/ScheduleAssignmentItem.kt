package com.bupt.schedule.ui.model

import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus

data class ScheduleAssignmentItem(
    val id: String,
    val assignmentId: String,
    val courseName: String,
    val title: String,
    val deadlineTimeShort: String,
    val day: Int,
    val deadlineSlot: Int,
    val status: AssignmentStatus,
    val tone: CourseTone,
    val original: Assignment,
)
