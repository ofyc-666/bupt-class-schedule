package com.bupt.schedule.ui

import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.ui.model.CourseTone
import com.bupt.schedule.ui.model.ScheduleItem

/** 0-based 领域节次到 1-based Canvas 节次的唯一转换点。 */
object ScheduleUiMapper {
    fun map(courses: List<Course>): List<ScheduleItem> = courses.map { course ->
        ScheduleItem(
            id = course.id,
            name = course.name,
            room = course.room,
            day = course.weekday,
            startPeriod = course.startSlot + 1,
            duration = course.endSlot - course.startSlot + 1,
            tone = TONES[Math.floorMod(course.id.hashCode(), TONES.size)],
        )
    }

    private val TONES = CourseTone.entries
}
