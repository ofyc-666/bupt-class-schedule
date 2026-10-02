package com.bupt.schedule.ui.model

enum class CourseTone {
    LILAC,
    SKY,
    ROSE,
    MINT,
    PEACH,
    BUTTER,
}

/** ScheduleView 专用的 1-based 布局模型。 */
data class ScheduleItem(
    val id: String,
    val name: String,
    val room: String,
    val day: Int,
    val startPeriod: Int,
    val duration: Int,
    val tone: CourseTone,
)
