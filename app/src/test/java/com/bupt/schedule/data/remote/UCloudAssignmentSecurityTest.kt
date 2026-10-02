package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Calendar

class UCloudAssignmentSecurityTest {

    @Test
    fun testNoWriteEndpointsOrMutatingMethodsExposed() {
        val clientClass = UCloudAssignmentClient::class.java
        val parserClass = UCloudAssignmentParser::class.java

        val forbiddenKeywords = listOf("submit", "upload", "create", "delete", "remove", "update", "modify", "postassignment")

        for (method in clientClass.declaredMethods) {
            val name = method.name.lowercase()
            for (kw in forbiddenKeywords) {
                assertFalse("Client should not expose mutating method '$name' matching '$kw'", name.contains(kw))
            }
        }

        for (method in parserClass.declaredMethods) {
            val name = method.name.lowercase()
            for (kw in forbiddenKeywords) {
                assertFalse("Parser should not expose mutating method '$name' matching '$kw'", name.contains(kw))
            }
        }
    }

    @Test
    fun testMultiCoursePartialFailureIsTolerated() {
        val dummyCredentials = Credentials("demo_account_1", "pass123")
        val course1 = UCloudCourseRecord("site_1", "Course 1")
        val course2 = UCloudCourseRecord("site_2", "Course 2")

        val assignment1 = Assignment(
            id = "assign_1",
            courseName = "Course 1",
            title = "Assignment 1",
            deadlineText = "2026-09-20 23:59",
            deadlineTimeShort = "23:59",
            status = AssignmentStatus.PENDING,
            week = 3,
            weekday = 7,
            deadlineSlot = 13,
        )

        val testClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                return UCloudSession("mock_token", "mock_user")
            }

            override fun fetchCourses(session: UCloudSession): List<UCloudCourseRecord> {
                return listOf(course1, course2)
            }

            override fun fetchQuizzesForCourse(session: UCloudSession, course: UCloudCourseRecord,
                termStartDate: String?, nowCalendar: Calendar): List<Assignment> = emptyList()

            override fun fetchAssignmentsForCourse(
                session: UCloudSession,
                course: UCloudCourseRecord,
                termStartDate: String?,
                nowCalendar: Calendar,
            ): List<Assignment> {
                if (course.siteId == "site_1") {
                    return listOf(assignment1)
                } else {
                    throw IOException("Network error fetching course 2 assignments")
                }
            }
        }

        val results = testClient.fetchAll(dummyCredentials, termStartDate = "2026-09-01")
        assertEquals(1, results.assignments.size)
        assertEquals("assign_1", results.assignments[0].id)
        assertTrue(results.successfulSiteIds.contains("site_1"))
        assertTrue(results.failedSiteIds.contains("site_2"))
        assertFalse(results.allCoursesSucceeded)
    }

    @Test
    fun testAllCoursesFailingThrowsException() {
        val dummyCredentials = Credentials("demo_account_1", "pass123")
        val course1 = UCloudCourseRecord("site_1", "Course 1")
        val course2 = UCloudCourseRecord("site_2", "Course 2")

        val testClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                return UCloudSession("mock_token", "mock_user")
            }

            override fun fetchCourses(session: UCloudSession): List<UCloudCourseRecord> {
                return listOf(course1, course2)
            }

            override fun fetchQuizzesForCourse(session: UCloudSession, course: UCloudCourseRecord,
                termStartDate: String?, nowCalendar: Calendar): List<Assignment> {
                throw IOException("Course failure on siteId: ${course.siteId}")
            }

            override fun fetchAssignmentsForCourse(
                session: UCloudSession,
                course: UCloudCourseRecord,
                termStartDate: String?,
                nowCalendar: Calendar,
            ): List<Assignment> {
                throw IOException("Course failure on siteId: ${course.siteId}")
            }
        }

        try {
            testClient.fetchAll(dummyCredentials, termStartDate = "2026-09-01")
            fail("Expected exception when all courses fail")
        } catch (expected: Exception) {
            assertTrue(expected is IOException)
            assertEquals("Course failure on siteId: site_1", expected.message)
        }
    }
}
