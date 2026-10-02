package com.bupt.schedule.data.local

import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.TaskType
import com.bupt.schedule.ui.model.CourseTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AssignmentStoreTest {

    @Test
    fun quizTypeSurvivesCacheAndCrossesEndAtBoundary() {
        val item = Assignment(
            id = "QUIZ:q1", courseName = "形式语言", title = "形式语言基础",
            deadlineText = "23:59", deadlineTimeShort = "23:59", status = AssignmentStatus.PENDING,
            week = 1, weekday = 7, deadlineSlot = 13,
            rawDeadline = "2026-09-27 23:59:00", taskType = TaskType.QUIZ,
        )
        val encoded = AssignmentJsonCodec.encode(listOf(item))
        val atDeadline = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI).apply {
            set(2026, Calendar.SEPTEMBER, 27, 23, 59, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val pending = AssignmentJsonCodec.decode(encoded, atDeadline).single()
        assertEquals(TaskType.QUIZ, pending.taskType)
        assertEquals(AssignmentStatus.PENDING, pending.status)
        atDeadline.add(Calendar.SECOND, 1)
        assertEquals(AssignmentStatus.OVERDUE,
            AssignmentJsonCodec.decode(encoded, atDeadline).single().status)
    }

    @Test
    fun testEncodeAndDecodeAssignments() {
        val list = listOf(
            Assignment(
                id = "a1",
                courseName = "Math",
                courseId = "c1",
                title = "Homework 1",
                deadlineText = "Sun 23:59",
                deadlineTimeShort = "23:59",
                status = AssignmentStatus.PENDING,
                week = 3,
                weekday = 7,
                deadlineSlot = 13,
                description = "Do exercises",
                score = null,
                remainingDaysText = "2 days",
                tone = CourseTone.SKY,
                chapterName = "Chapter 1",
            ),
            Assignment(
                id = "a2",
                courseName = "Physics",
                courseId = "c2",
                title = "Lab report",
                deadlineText = "Mon 18:00",
                deadlineTimeShort = "18:00",
                status = AssignmentStatus.OVERDUE,
                week = 2,
                weekday = 1,
                deadlineSlot = 9,
                description = "Submit report",
                score = null,
                remainingDaysText = "Overdue",
                tone = CourseTone.ROSE,
                chapterName = null,
            )
        )

        val encoded = AssignmentJsonCodec.encode(list)
        val decoded = AssignmentJsonCodec.decode(encoded)

        assertEquals(2, decoded.size)
        val first = decoded[0]
        assertEquals("a1", first.id)
        assertEquals("Math", first.courseName)
        assertEquals("c1", first.courseId)
        assertEquals("Homework 1", first.title)
        assertEquals("Sun 23:59", first.deadlineText)
        assertEquals("23:59", first.deadlineTimeShort)
        assertEquals(AssignmentStatus.PENDING, first.status)
        assertEquals(3, first.week)
        assertEquals(7, first.weekday)
        assertEquals(13, first.deadlineSlot)
        assertEquals("Do exercises", first.description)
        assertEquals("2 days", first.remainingDaysText)
        assertEquals(CourseTone.SKY, first.tone)
        assertEquals("Chapter 1", first.chapterName)

        val second = decoded[1]
        assertEquals("a2", second.id)
        assertEquals(AssignmentStatus.OVERDUE, second.status)
        assertEquals(null, second.chapterName)
    }

    @Test
    fun testDecodeEmptyList() {
        val decoded = AssignmentJsonCodec.decode("[]")
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun testCacheCrossingDeadlineRecomputesPendingToOverdue() {
        val shanghai = UCloudAssignmentParser.SHANGHAI
        val rawDeadline = "2026-09-17 12:00:00"

        val beforeCal = Calendar.getInstance(shanghai).apply {
            set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
        }
        val assignmentBefore = Assignment(
            id = "item_ddl",
            courseName = "Math",
            title = "Task",
            deadlineText = "Tomorrow 12:00",
            deadlineTimeShort = "12:00",
            status = AssignmentStatus.PENDING,
            week = 3,
            weekday = 4,
            deadlineSlot = 5,
            remainingDaysText = "1 day",
            rawDeadline = rawDeadline,
        )

        val encoded = AssignmentJsonCodec.encode(listOf(assignmentBefore))

        val decodedBefore = AssignmentJsonCodec.decode(encoded, beforeCal)
        assertEquals(AssignmentStatus.PENDING, decodedBefore[0].status)

        val afterCal = Calendar.getInstance(shanghai).apply {
            set(2026, Calendar.SEPTEMBER, 17, 13, 0, 0)
        }
        val decodedAfter = AssignmentJsonCodec.decode(encoded, afterCal)
        assertEquals(AssignmentStatus.OVERDUE, decodedAfter[0].status)
        assertEquals("\u5df2\u622a\u6b62", decodedAfter[0].remainingDaysText)
    }

    @Test
    fun testAccountIsolationAccountACannotReadAccountB() {
        val list = listOf(
            Assignment(
                id = "task1",
                courseName = "OS",
                title = "Lab 1",
                deadlineText = "2026-09-20 23:59",
                deadlineTimeShort = "23:59",
                status = AssignmentStatus.PENDING,
                week = 3,
                weekday = 7,
                deadlineSlot = 13,
            )
        )
        val encodedForA = AssignmentJsonCodec.encode("account_a", list)
        val decodedForA = AssignmentJsonCodec.decode(encodedForA, "account_a")
        assertEquals(1, decodedForA.size)
        assertEquals("task1", decodedForA[0].id)

        val decodedForB = AssignmentJsonCodec.decode(encodedForA, "account_b")
        assertTrue(decodedForB.isEmpty())

        val decodedNull = AssignmentJsonCodec.decode(encodedForA, null)
        assertTrue(decodedNull.isEmpty())
    }

    @Test
    fun testLegacyCacheWithoutOwnerCannotBeReused() {
        val legacyJson = """
            [
                {
                    "id": "legacy_task",
                    "course_name": "Network",
                    "title": "HW",
                    "deadline_text": "2026-09-20 23:59",
                    "deadline_time_short": "23:59",
                    "status": "PENDING",
                    "week": 3,
                    "weekday": 7,
                    "deadline_slot": 13
                }
            ]
        """.trimIndent()
        val decoded = AssignmentJsonCodec.decode(legacyJson, "any_account")
        assertTrue(decoded.isEmpty())
    }
}
