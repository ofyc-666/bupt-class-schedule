package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI

class SjdScheduleParserTest {
    @Test
    fun parsesSharedFixtureIntoZeroBasedDomainCourses() {
        val actual = SjdScheduleParser.parse(
            current = JSONObject(fixture("sjd-current-week.json")),
            curriculum = JSONObject(fixture("sjd-curriculum.json")),
            fallbackTermID = "unused",
            fallbackTermStartDate = "2000-01-03",
            fetchedAt = "2026-03-02T00:00:00Z",
        )

        assertEquals("2025-2026-2", actual.termID)
        assertEquals("2026-03-02", actual.termStartDate)
        assertEquals(2, actual.courses.size)
        with(actual.courses[0]) {
            assertEquals("示例课程", name)
            assertEquals("测试教师", teacher)
            assertEquals("教一楼-101", room)
            assertEquals((1..18).toList(), weekNumbers)
            assertEquals(1, weekday)
            assertEquals(2, startSlot)
            assertEquals(4, endSlot)
            assertEquals("fixture-course-1", sourceCourseID)
            assertEquals("09:50", startTime)
            assertEquals("12:15", endTime)
        }
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16, 18), actual.courses[1].weekNumbers)
    }

    @Test
    fun infersFirstMondayFromWeekZeroResponse() {
        val actual = SjdScheduleParser.parse(
            current = JSONObject(fixture("sjd-before-first-week.json")),
            curriculum = JSONObject(fixture("sjd-curriculum.json")),
            fallbackTermID = "unused",
            fallbackTermStartDate = "2000-01-03",
            fetchedAt = "2026-08-24T12:00:00+08:00",
        )

        assertEquals("2026-2027-1", actual.termID)
        assertEquals("2026-08-31", actual.termStartDate)
    }

    @Test
    fun parsesOddWeekTextWhenExplicitWeekDetailsAreMissing() {
        val curriculum = JSONObject(
            """{
              "code": 1,
              "data": [{
                "item": [{
                  "classWeek": "1-5单",
                  "classTime": "3030405",
                  "weekDay": "3",
                  "courseName": "单周示例",
                  "classroomName": "N101",
                  "jx0408id": "fixture-odd"
                }]
              }]
            }""".trimIndent(),
        )
        val actual = SjdScheduleParser.parse(
            current = JSONObject(fixture("sjd-current-week.json")),
            curriculum = curriculum,
            fallbackTermID = "unused",
            fallbackTermStartDate = "2000-01-03",
        ).courses.single()

        assertEquals(listOf(1, 3, 5), actual.weekNumbers)
        assertEquals(2, actual.startSlot)
        assertEquals(4, actual.endSlot)
    }

    @Test
    fun parsesTeachingClassFromKtmcField() {
        val curriculum = JSONObject(
            """{
              "code": 1,
              "data": [{
                "item": [{
                  "classWeek": "1-16",
                  "classTime": "1010203040506",
                  "weekDay": "1",
                  "courseName": "测试课程",
                  "classroomName": "教一楼-101",
                  "ktmc": "[DEMO01-DEMO05]班",
                  "startTime": "08:00",
                  "endTIme": "13:45",
                  "jx0408id": "fixture-ktmc-1"
                }]
              }]
            }""".trimIndent(),
        )
        val actual = SjdScheduleParser.parse(
            current = JSONObject(fixture("sjd-current-week.json")),
            curriculum = curriculum,
            fallbackTermID = "unused",
            fallbackTermStartDate = "2000-01-03",
        ).courses.single()

        assertEquals("[DEMO01-DEMO05]班", actual.teachingClass)
        assertEquals("08:00", actual.startTime)
        assertEquals("13:45", actual.endTime)
    }

    @Test
    fun defaultsTeachingClassToEmptyWhenMissing() {
        val actual = SjdScheduleParser.parse(
            current = JSONObject(fixture("sjd-current-week.json")),
            curriculum = JSONObject(fixture("sjd-curriculum.json")),
            fallbackTermID = "unused",
            fallbackTermStartDate = "2000-01-03",
        ).courses.first()

        assertEquals("", actual.teachingClass)
    }

    @Test
    fun rejectsUnsafeRedirectsAndOversizedResponses() {
        val current = URI.create("${SjdApiClient.ORIGIN}/bjyddx/login")
        val redirectError = runCatching {
            SjdRedirectPolicy.resolve(current, "https://example.com/login", 0)
        }.exceptionOrNull() as ScheduleException
        assertEquals(ScheduleFailureKind.INVALID_RESPONSE, redirectError.kind)

        val sizeError = runCatching {
            SjdResponseReader.read(
                ByteArrayInputStream(ByteArray(SjdInputLimits.MAX_RESPONSE_BYTES + 1)),
                -1,
            )
        }.exceptionOrNull() as ScheduleException
        assertEquals(ScheduleFailureKind.INVALID_RESPONSE, sizeError.kind)
    }

    @Test
    fun emptyCurriculumHasExplicitFailureKind() {
        val error = runCatching {
            SjdScheduleParser.parse(
                current = JSONObject(fixture("sjd-current-week.json")),
                curriculum = JSONObject("""{"code":1,"data":[]}"""),
                fallbackTermID = "unused",
                fallbackTermStartDate = "2000-01-03",
            )
        }.exceptionOrNull()

        assertTrue(error is ScheduleException)
        assertEquals(ScheduleFailureKind.EMPTY_SCHEDULE, (error as ScheduleException).kind)
    }

    private fun fixture(name: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name))
        return stream.bufferedReader().use { it.readText() }
    }
}
