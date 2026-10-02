package com.bupt.schedule.data.local

import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.domain.model.ScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleJsonCodecTest {
    @Test
    fun cacheRoundTripsEveryRequiredCourseField() {
        val expected = ScheduleSnapshot(
            termID = "2026-2027-1",
            termStartDate = "2026-09-07",
            fetchedAt = "2026-09-11T12:00:00+08:00",
            courses = listOf(
                Course(
                    id = "course-id",
                    name = "课程",
                    teacher = "教师",
                    room = "N101",
                    weekText = "1-16双",
                    weekNumbers = listOf(2, 4, 6, 8, 10, 12, 14, 16),
                    weekday = 5,
                    startSlot = 11,
                    endSlot = 13,
                    sourceCourseID = "source-id",
                    startTime = "18:30",
                    endTime = "20:55",
                    teachingClass = "[DEMO01-DEMO05]班",
                ),
            ),
        )

        assertEquals(expected, ScheduleJsonCodec.decode(ScheduleJsonCodec.encode(expected)))
    }

    @Test
    fun backwardCompatibleWithLegacyCacheWithoutTimes() {
        val legacyJson = """
        {
            "term_id": "2026-2027-1",
            "term_start_date": "2026-09-07",
            "fetched_at": "2026-09-11T12:00:00+08:00",
            "courses": [{
                "id": "c1",
                "name": "Legacy",
                "teacher": "T",
                "room": "R",
                "week_text": "1-16",
                "week_numbers": [1, 2],
                "weekday": 1,
                "start_slot": 0,
                "end_slot": 1
            }]
        }
        """.trimIndent()

        val snapshot = ScheduleJsonCodec.decode(legacyJson)
        assertEquals("", snapshot.courses[0].startTime)
        assertEquals("", snapshot.courses[0].endTime)
        assertEquals("", snapshot.courses[0].teachingClass)
    }
}
