package com.bupt.schedule.data.remote

import com.bupt.schedule.data.DefaultAssignmentRepository
import com.bupt.schedule.data.local.AssignmentJsonCodec
import com.bupt.schedule.data.local.AssignmentStore
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.TaskType
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UCloudCourseSurveyTest {
    private val session = UCloudSession("mock-token", "mock-student")
    private val credentials = Credentials("mock-student", "unused", "mock-password")

    @Test
    fun courseSurveyPagesShareTwoSlotsAndOnlyCompleteSourcesReplaceCache() {
        val firstWave = CountDownLatch(2)
        val surveyStarted = CountDownLatch(1)
        val surveyOverlappedWork = AtomicBoolean()
        val calls = ConcurrentLinkedQueue<String>()
        MockHttp { path, method, body ->
            val query = path.rawQuery.orEmpty().split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val route = when (path.path) {
                "/ykt-site/site/list/student/current" -> "courses/${query["current"]}"
                "/ykt-site/work/student/list" -> {
                    assertEquals("POST", method)
                    val request = JSONObject(body)
                    "work/${request.getString("siteId")}/${request.getInt("current")}"
                }
                "/ykt-site/examination/list-stu" -> "quiz/${query["siteId"]}/${query["current"]}"
                "/ykt-activity/survey/page/todo" -> {
                    assertEquals("GET", method)
                    assertEquals("1", query["level"])
                    assertEquals(session.userId, query["userId"])
                    assertEquals("100", query["size"])
                    "survey/${query["siteId"]}/${query["current"]}"
                }
                else -> error("Unexpected route")
            }
            calls.add(route)
            when (route) {
                "courses/1" -> page(1, 2, """{"id":"one","siteName":"课程一"}""", nested = true)
                "courses/2" -> page(2, 2, """{"id":"two","siteName":"课程二"}""", nested = true)
                "work/one/1" -> {
                    firstWave.countDown()
                    assertTrue(firstWave.await(5, SECONDS))
                    surveyOverlappedWork.set(surveyStarted.await(5, SECONDS))
                    page(1, 2, """{"id":"tentative","assignmentStatus":99,"endTime":"2020-01-01 12:00:00"}""")
                }
                "work/one/2" -> 503 to "{}"
                "quiz/one/1" -> {
                    firstWave.countDown()
                    assertTrue(firstWave.await(5, SECONDS))
                    page(1, 1, """{"id":"quiz","statusSelf":"未提交","endAt":"2020-01-01 12:00:00"}""")
                }
                "survey/one/1" -> {
                    surveyStarted.countDown()
                    page(1, 2, survey("survey-1"))
                }
                "survey/one/2" -> page(2, 2, survey("survey-1"), survey("survey-2"))
                "work/two/1", "quiz/two/1" -> page(1, 1)
                "survey/two/1" -> 200 to """{"data":{}}"""
                else -> error("Unexpected route $route")
            }
        }.use { mock ->
            val client = object : UCloudAssignmentClient() {
                override fun login(credentials: Credentials): UCloudSession = session
                override fun openConnection(uri: URI): HttpURLConnection = mock.open(uri)
            }
            val oldWork = old("old-work", "one", TaskType.ASSIGNMENT)
            val oldSurvey = old("SURVEY:old", "two", TaskType.SURVEY)
            val store = object : AssignmentStore() {
                var saved = listOf(oldWork, oldSurvey, old("old-work-two", "two", TaskType.ASSIGNMENT))
                override fun load(account: String?, nowCalendar: Calendar): List<Assignment> = saved
                override fun save(account: String, assignments: List<Assignment>) {
                    saved = assignments
                }
            }
            val result = DefaultAssignmentRepository(client, store).refreshWithCoverage(credentials, null)
            assertFalse(result.complete)
            assertTrue(surveyOverlappedWork.get())
            assertEquals(2, mock.peak.get())
            assertEquals(10, calls.size)
            assertEquals(10, calls.toSet().size)
            assertEquals(result.assignments, store.saved)
            assertEquals(setOf("old-work", "SURVEY:old", "QUIZ:quiz", "SURVEY:survey-1", "SURVEY:survey-2"),
                result.assignments.map { it.id }.toSet())
            val fresh = result.assignments.filter { it.taskType == TaskType.SURVEY && it.courseId == "one" }
            assertTrue(fresh.all { it.status == AssignmentStatus.OVERDUE && it.courseName == "课程一" })
        }
    }

    @Test
    fun surveyCacheKeepsItsTypeAndCourseOwnership() {
        val item = UCloudAssignmentParser.parseSurvey(JSONObject(survey("shared")), "one", "课程一", null)
        val sameRemoteId = item.copy(courseId = "two", courseName = "课程二")
        val merged = UCloudAssignmentParser.mergeAndSort(listOf(item, item, sameRemoteId))
        assertEquals(2, merged.size)
        val cached = AssignmentJsonCodec.decode(AssignmentJsonCodec.encode(credentials.account, merged), credentials.account)
        assertEquals(merged, cached)
        assertTrue(AssignmentJsonCodec.decode(AssignmentJsonCodec.encode(credentials.account, merged), "other").isEmpty())
    }

    private fun page(current: Int, pages: Int, vararg records: String, nested: Boolean = false): Pair<Int, String> {
        val data = """{"current":$current,"pages":$pages,"records":[${records.joinToString()}]}"""
        return 200 to if (nested) """{"data":{"data":$data}}""" else """{"data":$data}"""
    }

    private fun survey(id: String) =
        """{"id":"$id","theme":"测试问卷","siteId":"one","endTime":"2020-01-01 12:00:00"}"""

    private fun old(id: String, siteId: String, type: TaskType) = Assignment(
        id, siteId, siteId, id, "", "", AssignmentStatus.OVERDUE, 1, 1, 0, taskType = type,
    )

    private class MockHttp(private val answer: (URI, String, String) -> Pair<Int, String>) : Closeable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        private val workers = Executors.newCachedThreadPool()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val active = AtomicInteger()
        val peak = AtomicInteger()
        private val listener = Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (error: Exception) {
                    if (!server.isClosed) errors.add(error)
                    break
                }
                workers.execute { socket.use(::handle) }
            }
        }.apply { start() }

        fun open(uri: URI): HttpURLConnection =
            URL("http://127.0.0.1:${server.localPort}${uri.rawPath}?${uri.rawQuery.orEmpty()}")
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection

        private fun handle(socket: Socket) {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val response = try {
                val (method, target) = line(input).split(' ')
                var header = line(input)
                var length = 0
                while (header.isNotEmpty()) {
                    if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    header = line(input)
                }
                val body = String(ByteArray(length) { input.read().toByte() }, UTF_8)
                val running = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, running) }
                try {
                    answer(URI.create(target), method, body)
                } finally {
                    active.decrementAndGet()
                }
            } catch (error: Throwable) {
                errors.add(error)
                500 to "{}"
            }
            val (status, body) = response
            val bytes = body.toByteArray(UTF_8)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 $status Mock\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(UTF_8))
            output.write(bytes)
            output.flush()
        }

        private fun line(input: BufferedInputStream): String = buildString {
            while (true) {
                val next = input.read()
                check(next >= 0)
                if (next == '\n'.code) break
                if (next != '\r'.code) append(next.toChar())
            }
        }

        override fun close() {
            server.close()
            listener.join()
            workers.shutdown()
            assertTrue(workers.awaitTermination(6, SECONDS))
            errors.peek()?.let { throw AssertionError("Unexpected mock request", it) }
        }
    }
}
