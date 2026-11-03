package com.bupt.schedule.domain.model

import com.bupt.schedule.ui.model.CourseTone

enum class AssignmentStatus(val label: String) {
    PENDING("待提交"),
    SUBMITTED("已提交"),
    GRADED("已批改"),
    OVERDUE("待提交");

    val isFinished: Boolean
        get() = this == SUBMITTED || this == GRADED
}

enum class TaskType(val label: String) {
    ASSIGNMENT("作业"),
    QUIZ("测验"),
    SURVEY("问卷");

    fun isOverdue(deadlineMillis: Long, nowMillis: Long): Boolean =
        if (this == QUIZ) deadlineMillis < nowMillis else deadlineMillis <= nowMillis
}

data class Assignment(
    val id: String,
    val courseName: String,
    val courseId: String? = null,
    val title: String,
    val deadlineText: String,
    val deadlineTimeShort: String,
    val status: AssignmentStatus,
    val week: Int,
    val weekday: Int,
    val deadlineSlot: Int,
    val description: String = "",
    val score: String? = null,
    val remainingDaysText: String = "",
    val tone: CourseTone = CourseTone.SKY,
    val chapterName: String? = null,
    val rawDeadline: String? = null,
    val taskType: TaskType = TaskType.ASSIGNMENT,
    val isCustom: Boolean = false,
) {
    internal val sourceKey get() = Triple(courseId, taskType, id)

    init {
        require(weekday in 1..7) { "weekday 必须在 1 到 7 之间" }
        require(deadlineSlot in 0..13) { "deadlineSlot 必须在 0 到 13 之间" }
    }
}
