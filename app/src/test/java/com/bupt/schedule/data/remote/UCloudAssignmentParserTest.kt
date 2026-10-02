package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.TaskType
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar

class UCloudAssignmentParserTest {

    private val shanghai = UCloudAssignmentParser.SHANGHAI

    @Test
    fun quizUsesStatusSelfAndEndAtIncludingDeadlineBoundary() {
        val deadline = Calendar.getInstance(shanghai).apply {
            set(2026, Calendar.SEPTEMBER, 27, 23, 59, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val pending = JSONObject()
            .put("id", "2102367369066188802")
            .put("title", "形式语言基础")
            .put("state", 1)
            .put("statusSelf", "未提交")
            .put("endAt", "2026-09-27 23:59:00")
        val atDeadline = UCloudAssignmentParser.parseQuiz(pending, "site-1", "形式语言", null, deadline)
        assertNotNull(atDeadline)
        assertEquals("QUIZ:2102367369066188802", atDeadline?.id)
        assertEquals(TaskType.QUIZ, atDeadline?.taskType)
        assertEquals(AssignmentStatus.PENDING, atDeadline?.status)
        val later = deadline.clone() as Calendar
        later.add(Calendar.SECOND, 1)
        assertEquals(AssignmentStatus.OVERDUE,
            UCloudAssignmentParser.parseQuiz(pending, "site-1", "形式语言", null, later)?.status)

        val graded = JSONObject(pending.toString()).put("statusSelf", "已评分")
        assertNull(UCloudAssignmentParser.parseQuiz(graded, "site-1", "形式语言", null, deadline))
    }

    @Test
    fun testOnlyAssignmentStatus99IsPreserved() {
        val nowCal = Calendar.getInstance(shanghai).apply {
            set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
        }

        val json99 = JSONObject()
            .put("id", "hw_99")
            .put("assignmentTitle", "Task 99")
            .put("assignmentStatus", 99)
            .put("assignmentEndTime", "2026-09-20 23:59:59")

        val assignment99 = UCloudAssignmentParser.parseAssignment(
            raw = json99,
            siteId = "site_1",
            siteName = "Math",
            termStartDate = "2026-09-01",
            nowCalendar = nowCal,
        )
        assertNotNull(assignment99)
        assertEquals("hw_99", assignment99?.id)
        assertEquals("Math", assignment99?.courseName)

        for (status in listOf(0, 1, 2, 3, -1, 100)) {
            val json = JSONObject()
                .put("id", "hw_$status")
                .put("assignmentTitle", "Task $status")
                .put("assignmentStatus", status)
                .put("assignmentEndTime", "2026-09-20 23:59:59")

            val parsed = UCloudAssignmentParser.parseAssignment(
                raw = json,
                siteId = "site_1",
                siteName = "Math",
                termStartDate = "2026-09-01",
                nowCalendar = nowCal,
            )
            assertNull("assignmentStatus $status must be filtered out", parsed)
        }
    }

    @Test
    fun testAssignmentTypeDoesNotFilterOut() {
        val nowCal = Calendar.getInstance(shanghai).apply {
            set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
        }

        for (type in listOf("1", "2", "exam", "group", "normal", "")) {
            val json = JSONObject()
                .put("id", "hw_type_$type")
                .put("assignmentTitle", "Task $type")
                .put("assignmentStatus", 99)
                .put("assignmentType", type)
                .put("assignmentEndTime", "2026-09-20 23:59:59")

            val parsed = UCloudAssignmentParser.parseAssignment(
                raw = json,
                siteId = "site_1",
                siteName = "Math",
                termStartDate = "2026-09-01",
                nowCalendar = nowCal,
            )
            assertNotNull("assignmentType $type should not be filtered when status is 99", parsed)
        }
    }

    @Test
    fun testStatusPendingAndOverdueDetermination() {
        val nowCal = Calendar.getInstance(shanghai).apply {
            set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
        }

        val futureJson = JSONObject()
            .put("id", "hw_future")
            .put("assignmentTitle", "Future Task")
            .put("assignmentStatus", 99)
            .put("assignmentEndTime", "2026-09-17 18:00:00")

        val futureAssignment = UCloudAssignmentParser.parseAssignment(
            raw = futureJson,
            siteId = "site_1",
            siteName = "Course 1",
            termStartDate = "2026-09-01",
            nowCalendar = nowCal,
        )
        assertNotNull(futureAssignment)
        assertEquals(AssignmentStatus.PENDING, futureAssignment?.status)

        val pastJson = JSONObject()
            .put("id", "hw_past")
            .put("assignmentTitle", "Past Task")
            .put("assignmentStatus", 99)
            .put("assignmentEndTime", "2026-09-15 18:00:00")

        val pastAssignment = UCloudAssignmentParser.parseAssignment(
            raw = pastJson,
            siteId = "site_1",
            siteName = "Course 1",
            termStartDate = "2026-09-01",
            nowCalendar = nowCal,
        )
        assertNotNull(pastAssignment)
        assertEquals(AssignmentStatus.OVERDUE, pastAssignment?.status)
    }

    @Test
    fun testEndTimeFormatsCompatibility() {
        val parsedSec = UCloudAssignmentParser.parseDeadlineDate("2026-09-20 23:59:59")
        assertNotNull(parsedSec)
        assertEquals(2026, parsedSec?.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, parsedSec?.get(Calendar.MONTH))
        assertEquals(20, parsedSec?.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, parsedSec?.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, parsedSec?.get(Calendar.MINUTE))
        assertEquals(59, parsedSec?.get(Calendar.SECOND))

        val parsedMin = UCloudAssignmentParser.parseDeadlineDate("2026-09-20 23:59")
        assertNotNull(parsedMin)
        assertEquals(2026, parsedMin?.get(Calendar.YEAR))
        assertEquals(20, parsedMin?.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, parsedMin?.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, parsedMin?.get(Calendar.MINUTE))
    }

    @Test
    fun testSlotMappingBounds() {
        assertEquals(0, UCloudAssignmentParser.mapTimeToSlot("06:30"))
        assertEquals(0, UCloudAssignmentParser.mapTimeToSlot("08:00"))
        assertEquals(0, UCloudAssignmentParser.mapTimeToSlot("08:45"))
        assertEquals(1, UCloudAssignmentParser.mapTimeToSlot("08:50"))
        assertEquals(13, UCloudAssignmentParser.mapTimeToSlot("20:55"))
        assertEquals(13, UCloudAssignmentParser.mapTimeToSlot("21:30"))
        assertEquals(13, UCloudAssignmentParser.mapTimeToSlot("23:59"))
    }

    @Test
    fun testPaginationParsing() {
        val json = JSONObject()
            .put("code", 200)
            .put(
                "data",
                JSONObject()
                    .put("total", 35)
                    .put("size", 10)
                    .put("current", 2)
                    .put("pages", 4)
                    .put(
                        "records",
                        JSONArray().apply {
                            put(JSONObject().put("id", "item1").put("assignmentStatus", 99).put("assignmentEndTime", "2026-09-20 23:59"))
                            put(JSONObject().put("id", "item2").put("assignmentStatus", 0).put("assignmentEndTime", "2026-09-20 23:59"))
                        }
                    )
            )

        val page = UCloudAssignmentParser.parseAssignmentPage(json)
        assertEquals(35, page.total)
        assertEquals(2, page.current)
        assertEquals(4, page.pages)
        assertEquals(2, page.records.size)
    }

    @Test
    fun testCleanHtmlDetailContent() {
        val html = "<p>Please finish before <strong>deadline</strong>:<br/>1. Read Chapter 3<br/>2. Exercise &amp; report</p>"
        val cleaned = UCloudAssignmentParser.cleanHtml(html)
        assertTrue(cleaned.contains("Please finish before deadline:"))
        assertTrue(cleaned.contains("1. Read Chapter 3"))
        assertTrue(cleaned.contains("2. Exercise & report"))
        assertTrue(!cleaned.contains("<p>"))
        assertTrue(!cleaned.contains("<strong>"))
        assertTrue(!cleaned.contains("&amp;"))
    }

    @Test
    fun testParseApiRootSuccess() {
        val json = """{"code":200,"success":true,"data":{"records":[]},"msg":"ok"}"""
        val root = UCloudAssignmentParser.parseApiRoot(json, "test")
        assertEquals(200, root.getInt("code"))
        assertTrue(root.getBoolean("success"))
    }

    @Test
    fun testParseApiRootHttp200WithNon200CodeFails() {
        val json = """{"code":401,"success":false,"msg":"User session expired bearer xyz123"}"""
        try {
            UCloudAssignmentParser.parseApiRoot(json, "course")
            fail("Expected ScheduleException on code 401")
        } catch (e: ScheduleException) {
            assertEquals(ScheduleFailureKind.INVALID_RESPONSE, e.kind)
            assertTrue("Should sanitize token", !e.message.orEmpty().contains("xyz123"))
            assertTrue("Should mask token with [PROTECTED]", e.message.orEmpty().contains("[PROTECTED]"))
        }
    }

    @Test
    fun testParseApiRootSuccessFalseFails() {
        val json = """{"code":200,"success":false,"msg":"Database timeout"}"""
        try {
            UCloudAssignmentParser.parseApiRoot(json, "assignment")
            fail("Expected ScheduleException when success is false")
        } catch (e: ScheduleException) {
            assertEquals(ScheduleFailureKind.INVALID_RESPONSE, e.kind)
            assertTrue(e.message.orEmpty().contains("Database timeout"))
        }
    }

    @Test
    fun testBusinessFailureDoesNotGetTreatedAsEmptyCoursesOrAssignments() {
        val failureBody = """{"code":401,"success":false,"data":null,"msg":"Unauthorized"}"""
        try {
            val root = UCloudAssignmentParser.parseApiRoot(failureBody, "course")
            UCloudAssignmentParser.parseCourseRecords(root)
            fail("Should have failed at parseApiRoot, not returned empty list")
        } catch (e: ScheduleException) {
            assertEquals(ScheduleFailureKind.INVALID_RESPONSE, e.kind)
        }
    }

    @Test
    fun testSanitizeErrorMessageMasksSecrets() {
        val dirty = "Error ticket=ST-12345 token=ABCDE bearer eyJhbGciOi password=mypass cookie=SESSIONID=999"
        val sanitized = UCloudAssignmentParser.sanitizeErrorMessage(dirty)
        assertTrue(!sanitized.contains("ST-12345"))
        assertTrue(!sanitized.contains("ABCDE"))
        assertTrue(!sanitized.contains("eyJhbGciOi"))
        assertTrue(!sanitized.contains("mypass"))
        assertTrue(!sanitized.contains("999"))
        assertTrue(sanitized.contains("[PROTECTED]"))
    }
}
