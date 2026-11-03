package com.bupt.schedule.data

import com.bupt.schedule.data.local.AssignmentStore
import com.bupt.schedule.data.remote.UCloudAssignmentClient
import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.data.remote.UCloudSession
import com.bupt.schedule.data.remote.UCloudSyncResult
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.TaskType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class DefaultAssignmentRepositoryTest {

    @Test
    fun clearingSessionPreventsAnInFlightRefreshFromWritingOldAccountData() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = createAssignment("old", "site", "课程")
        val store = FakeAssignmentStore(listOf(old))
        val client = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials) = UCloudSession("token", "user")
            override fun fetchAll(session: UCloudSession, termStartDate: String?, nowCalendar: Calendar): UCloudSyncResult {
                entered.countDown()
                check(release.await(5, SECONDS))
                return UCloudSyncResult(emptyList(), emptySet(), emptySet(), true)
            }
        }
        val repository = DefaultAssignmentRepository(client, store)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Throwable?> {
                runCatching { repository.refresh(Credentials("old-account", "password", "cloud"), null) }
                    .exceptionOrNull()
            }
            assertTrue(entered.await(5, SECONDS))
            repository.clearSession()
            release.countDown()
            assertTrue(result.get(5, SECONDS) is InterruptedException)
            assertEquals(listOf(old), store.inMemoryAssignments)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun cloudSuccessPartialFailureNoPasswordAndSessionClearNeverChangeLocalTasks() {
        val files = mutableMapOf<String, String>()
        val localStore = com.bupt.schedule.data.local.CustomAssignmentStore(object : com.bupt.schedule.data.local.CustomAssignmentStore.Storage {
            override fun read(ownerHash: String) = files[ownerHash]
            override fun write(ownerHash: String, value: String) { files[ownerHash] = value }
        })
        val local = com.bupt.schedule.domain.model.CustomAssignment(title = "Local", courseName = "Course", deadlineMillis = 1791734340000)
        localStore.upsert("demo_account_1", local)
        val originalBytes = files.toMap()
        var mode = "success"
        val item = createAssignment("cloud", "site_1", "Course")
        val client = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials) = UCloudSession("test-token", "test-user")
            override fun fetchAll(session: UCloudSession, termStartDate: String?, nowCalendar: Calendar): UCloudSyncResult {
                if (mode == "failure") throw IOException("test failure")
                return UCloudSyncResult(listOf(item), setOf("site_1"), if (mode == "partial") setOf("site_2") else emptySet(), mode != "partial")
            }
        }
        val repo = DefaultAssignmentRepository(client, FakeAssignmentStore())
        val credentials = Credentials("demo_account_1", "test-password", "test-cloud-password")
        for (next in listOf("success", "partial", "failure")) {
            mode = next
            runCatching { repo.refresh(credentials, "2026-09-07") }
            val merged = com.bupt.schedule.domain.logic.AssignmentPresentation.merge(repo.loadCached(credentials.account),
                localStore.load(credentials.account), "2026-09-07", UCloudAssignmentParser.parseDeadlineDate("2026-10-01 00:00:00")!!)
            assertEquals(2, merged.size)
            assertTrue(merged.any { it.id == local.id && it.isCustom })
            assertEquals(originalBytes, files)
        }
        repo.refresh(credentials.copy(teachingCloudPassword = null), "2026-09-07")
        repo.clearSession()
        assertEquals(originalBytes, files)
        assertEquals(listOf(local), localStore.load(credentials.account))
        assertTrue(localStore.load("another-demo-account").isEmpty())
    }

    @Test
    fun completedAssignmentDisappearsAfterSuccessfulRefresh() {
        var upstreamStatus = 99
        val store = FakeAssignmentStore()
        val client = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials) = UCloudSession("token", "user")

            override fun fetchAll(session: UCloudSession, termStartDate: String?, nowCalendar: Calendar): UCloudSyncResult {
                val raw = JSONObject()
                    .put("id", "homework-1")
                    .put("assignmentTitle", "Homework")
                    .put("assignmentStatus", upstreamStatus)
                    .put("assignmentEndTime", "2030-09-20 12:00:00")
                val assignments = listOfNotNull(
                    UCloudAssignmentParser.parseAssignment(raw, "site_1", "Course 1", termStartDate, nowCalendar)
                )
                return UCloudSyncResult(assignments, setOf("site_1"), emptySet(), true)
            }
        }
        val repository = DefaultAssignmentRepository(client = client, store = store)
        val credentials = Credentials("demo_account_1", "password", "cloud_pwd")

        assertEquals(listOf("homework-1"), repository.refresh(credentials, null).map { it.id })
        assertEquals(listOf("homework-1"), store.inMemoryAssignments.map { it.id })

        upstreamStatus = 1
        assertTrue(repository.refresh(credentials, null).isEmpty())
        assertTrue(store.inMemoryAssignments.isEmpty())
    }

    @Test
    fun testPartialRemovalUsesSuccessfulSourceForEachTaskType() {
        for (failedType in TaskType.entries) {
            val oldAssignment = createAssignment("old-assignment", "site_1", "Course 1")
            val oldQuiz = createAssignment("QUIZ:old", "site_1", "Course 1")
                .copy(taskType = TaskType.QUIZ)
            val successfulType = if (failedType == TaskType.QUIZ) TaskType.ASSIGNMENT else TaskType.QUIZ
            val newItem = createAssignment("new-${successfulType.name}", "site_1", "Course 1")
                .copy(taskType = successfulType)
            val store = FakeAssignmentStore(listOf(oldAssignment, oldQuiz))
            val fakeClient = object : UCloudAssignmentClient() {
                override fun login(credentials: Credentials) = UCloudSession("token", "user")
                override fun fetchAll(session: UCloudSession, termStartDate: String?, nowCalendar: Calendar) =
                    UCloudSyncResult(
                        assignments = listOf(newItem), successfulSiteIds = emptySet(),
                        failedSiteIds = setOf("site_1"), allCoursesSucceeded = false,
                        successfulAssignmentSiteIds = if (successfulType == TaskType.ASSIGNMENT) setOf("site_1") else emptySet(),
                        successfulQuizSiteIds = if (successfulType == TaskType.QUIZ) setOf("site_1") else emptySet(),
                    )
            }
            val result = DefaultAssignmentRepository(client = fakeClient, store = store)
                .refresh(Credentials("demo_account_1", "password", "cloud_pwd"), "2026-09-01")
            val retainedOld = if (failedType == TaskType.QUIZ) oldQuiz.id else oldAssignment.id
            assertEquals(setOf(retainedOld, newItem.id), result.map { it.id }.toSet())
        }
    }

    private class FakeAssignmentStore(
        val accountMap: MutableMap<String, List<Assignment>> = mutableMapOf(),
    ) : AssignmentStore() {
        constructor(initial: List<Assignment>, account: String = "demo_account_1") : this(
            mutableMapOf(account to initial)
        )

        var inMemoryAssignments: List<Assignment>
            get() = accountMap["demo_account_1"].orEmpty()
            set(value) { accountMap["demo_account_1"] = value }

        override fun load(account: String?, nowCalendar: Calendar): List<Assignment> {
            if (account.isNullOrBlank()) return emptyList()
            return accountMap[account].orEmpty()
        }

        override fun save(account: String, assignments: List<Assignment>) {
            accountMap[account] = assignments
        }

        override fun load(nowCalendar: Calendar): List<Assignment> = inMemoryAssignments

        override fun save(assignments: List<Assignment>) {
            inMemoryAssignments = assignments
        }
    }

    private fun createAssignment(id: String, courseId: String, courseName: String): Assignment {
        return Assignment(
            id = id,
            courseName = courseName,
            courseId = courseId,
            title = "Task $id",
            deadlineText = "Tomorrow 12:00",
            deadlineTimeShort = "12:00",
            status = AssignmentStatus.PENDING,
            week = 1,
            weekday = 2,
            deadlineSlot = 3,
            rawDeadline = "2026-09-20 12:00:00",
        )
    }

    @Test
    fun testSingleCASLoginPerRefresh() {
        var loginCount = 0
        val fakeClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                loginCount++
                return UCloudSession("token_123", "user_123")
            }

            override fun fetchAll(
                session: UCloudSession,
                termStartDate: String?,
                nowCalendar: Calendar,
            ): UCloudSyncResult {
                return UCloudSyncResult(
                    assignments = emptyList(),
                    successfulSiteIds = emptySet(),
                    failedSiteIds = emptySet(),
                    allCoursesSucceeded = true,
                )
            }
        }

        val store = FakeAssignmentStore()
        val repo = DefaultAssignmentRepository(client = fakeClient, store = store)
        repo.refresh(Credentials("demo_account_1", "password", "cloud_pwd"), termStartDate = "2026-09-01")

        assertEquals(1, loginCount)
    }

    @Test
    fun testPartialCourseFailureRetainsOldCacheOfFailedCourse() {
        val oldA1 = createAssignment("a1", "site_1", "Course 1")
        val oldA2 = createAssignment("a2", "site_2", "Course 2")
        val newA1 = createAssignment("a1_new", "site_1", "Course 1")

        val store = FakeAssignmentStore(listOf(oldA1, oldA2))

        val fakeClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                return UCloudSession("token_123", "user_123")
            }

            override fun fetchAll(
                session: UCloudSession,
                termStartDate: String?,
                nowCalendar: Calendar,
            ): UCloudSyncResult {
                return UCloudSyncResult(
                    assignments = listOf(newA1),
                    successfulSiteIds = setOf("site_1"),
                    failedSiteIds = setOf("site_2"),
                    allCoursesSucceeded = false,
                )
            }
        }

        val repo = DefaultAssignmentRepository(client = fakeClient, store = store)
        val result = repo.refresh(Credentials("demo_account_1", "password", "cloud_pwd"), termStartDate = "2026-09-01")

        assertEquals(2, result.size)
        val ids = result.map { it.id }.toSet()
        assertTrue("Must include new assignment for successful course", ids.contains("a1_new"))
        assertTrue("Must retain old assignment for failed course", ids.contains("a2"))
        assertTrue("Must not retain old assignment for successful course", !ids.contains("a1"))

        assertEquals(2, store.inMemoryAssignments.size)
    }

    @Test
    fun testSuccessfulCourseClearsCompletedAssignmentsEvenIfEmpty() {
        val oldA1 = createAssignment("a1", "site_1", "Course 1")
        val oldA2 = createAssignment("a2", "site_2", "Course 2")

        val store = FakeAssignmentStore(listOf(oldA1, oldA2))

        val fakeClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                return UCloudSession("token_123", "user_123")
            }

            override fun fetchAll(
                session: UCloudSession,
                termStartDate: String?,
                nowCalendar: Calendar,
            ): UCloudSyncResult {
                return UCloudSyncResult(
                    assignments = emptyList(),
                    successfulSiteIds = setOf("site_1"),
                    failedSiteIds = setOf("site_2"),
                    allCoursesSucceeded = false,
                )
            }
        }

        val repo = DefaultAssignmentRepository(client = fakeClient, store = store)
        val result = repo.refresh(Credentials("demo_account_1", "password", "cloud_pwd"), termStartDate = "2026-09-01")

        assertEquals(1, result.size)
        assertEquals("a2", result[0].id)
        assertEquals(1, store.inMemoryAssignments.size)
        assertEquals("a2", store.inMemoryAssignments[0].id)
    }

    @Test
    fun testAllCoursesFailThrowsExceptionAndDoesNotOverwriteStore() {
        val oldA1 = createAssignment("a1", "site_1", "Course 1")
        val store = FakeAssignmentStore(listOf(oldA1))

        val fakeClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                return UCloudSession("token_123", "user_123")
            }

            override fun fetchAll(
                session: UCloudSession,
                termStartDate: String?,
                nowCalendar: Calendar,
            ): UCloudSyncResult {
                throw IOException("All course endpoints failed")
            }
        }

        val repo = DefaultAssignmentRepository(client = fakeClient, store = store)
        try {
            repo.refresh(Credentials("demo_account_1", "password", "cloud_pwd"), termStartDate = "2026-09-01")
            fail("Expected exception when all courses fail")
        } catch (e: IOException) {
            assertEquals("All course endpoints failed", e.message)
        }

        assertEquals(1, store.inMemoryAssignments.size)
        assertEquals("a1", store.inMemoryAssignments[0].id)
    }

    @Test
    fun testPartialSyncDoesNotMergeOtherAccountCache() {
        val oldA1 = createAssignment("a1", "site_1", "Course 1")
        val oldA2 = createAssignment("a2", "site_2", "Course 2")

        val store = FakeAssignmentStore()
        store.save("account_A", listOf(oldA1, oldA2))

        val fakeClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                return UCloudSession("token_b", "user_b")
            }

            override fun fetchAll(
                session: UCloudSession,
                termStartDate: String?,
                nowCalendar: Calendar,
            ): UCloudSyncResult {
                return UCloudSyncResult(
                    assignments = emptyList(),
                    successfulSiteIds = setOf("site_1"),
                    failedSiteIds = setOf("site_2"),
                    allCoursesSucceeded = false,
                )
            }
        }

        val repo = DefaultAssignmentRepository(client = fakeClient, store = store)
        val result = repo.refresh(Credentials("account_B", "password", "cloud_pwd"), termStartDate = "2026-09-01")

        assertTrue("Account B must not merge Account A cache on partial sync failure", result.isEmpty())
        assertTrue("Account B stored cache must be empty", store.load("account_B").isEmpty())
        assertEquals("Account A stored cache must remain untouched", 2, store.load("account_A").size)
    }
    @Test
    fun testRefreshWithoutCloudPasswordDoesNotAttemptLogin() {
        var loginAttempted = false
        val fakeClient = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                loginAttempted = true
                return UCloudSession("token", "user")
            }
        }
        val store = FakeAssignmentStore(listOf(createAssignment("a1", "c1", "Course 1")))
        val repo = DefaultAssignmentRepository(client = fakeClient, store = store)
        val result = repo.refresh(Credentials("demo_account_1", "password", null), termStartDate = "2026-09-01")
        org.junit.Assert.assertFalse("Login must not be attempted when teachingCloudPassword is null", loginAttempted)
        assertEquals(1, result.size)
        assertEquals("a1", result[0].id)
    }
    @Test
    fun testValidPasswordRefreshSavesLocalCache() {
        val item = createAssignment("new", "site", "Course")
        val store = FakeAssignmentStore()
        var loginCount = 0
        val client = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                loginCount++
                return UCloudSession("token", "user")
            }
            override fun fetchAll(session: UCloudSession, termStartDate: String?, nowCalendar: Calendar) =
                UCloudSyncResult(listOf(item), setOf("site"), emptySet(), true)
        }
        val repo = DefaultAssignmentRepository(client = client, store = store)
        repo.refresh(Credentials("demo_account_1", "jw", "ucloud"), null)
        assertEquals(1, loginCount)
        assertEquals(listOf(item), store.inMemoryAssignments)
    }

    @Test
    fun testFailedPasswordVerificationDoesNotChangeCache() {
        val old = createAssignment("old", "site", "Course")
        val store = FakeAssignmentStore(listOf(old))
        val client = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession = throw IOException("Invalid password")
        }
        val repo = DefaultAssignmentRepository(client = client, store = store)
        try {
            repo.verifyCredentials(Credentials("demo_account_1", "jw", "wrong"))
            fail("Expected login failure")
        } catch (_: IOException) {
            assertEquals(listOf(old), store.inMemoryAssignments)
        }
    }

    @Test
    fun testRefreshFailureDueToNetworkTimeoutPreservesExistingCache() {
        val oldAssignment = createAssignment("cached-assignment", "site_1", "Old Course")
        val store = FakeAssignmentStore(listOf(oldAssignment))
        val client = object : UCloudAssignmentClient() {
            override fun login(credentials: Credentials): UCloudSession {
                throw IOException("Connection timed out")
            }
        }
        val credentials = Credentials("demo_account_1", "jw_pwd", "ucloud_pwd")
        val repo = DefaultAssignmentRepository(client = client, store = store)
        try {
            repo.refresh(credentials, "2026-09-01")
            fail("Expected network IOException")
        } catch (_: IOException) {
            assertEquals(listOf(oldAssignment), store.inMemoryAssignments)
            assertEquals("ucloud_pwd", credentials.teachingCloudPassword)
        }
    }
}
