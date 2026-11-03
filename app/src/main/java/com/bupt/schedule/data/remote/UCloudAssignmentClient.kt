// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study UCloudAssignmentClient.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import com.bupt.schedule.domain.model.TaskType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class UCloudSession(
    val accessToken: String,
    val userId: String,
)

internal data class UCloudHttpResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
)

open class UCloudAssignmentClient {
    companion object {
        private const val CAS_LOGIN_URL =
            "https://auth.bupt.edu.cn/authserver/login?service=https%3A%2F%2Fucloud.bupt.edu.cn"
        private const val SERVICE_ORIGIN = "https://ucloud.bupt.edu.cn"
        private const val API_ORIGIN = "https://apiucloud.bupt.edu.cn"
        private const val AUTH_HOST = "auth.bupt.edu.cn"
        private const val UCLOUD_HOST = "ucloud.bupt.edu.cn"
        private const val API_HOST = "apiucloud.bupt.edu.cn"
        // OAuth client identifier embedded in the UCloud web client for login compatibility.
        // It is not a user credential or a project-specific secret.
        private const val PORTAL_AUTHORIZATION = "Basic  cG9ydGFsOnBvcnRhbF9zZWNyZXQ="
        private const val TENANT_ID = "000000"

        private const val MAXIMUM_LOGIN_BYTES = 1 * 1024 * 1024
        private const val MAXIMUM_TOKEN_BYTES = 512 * 1024
        private const val MAXIMUM_API_BYTES = 8 * 1024 * 1024
        private const val MAXIMUM_COOKIE_BYTES = 16 * 1024
        private const val MAXIMUM_ASSIGNMENTS = 5000
        private const val MAX_PAGES = 20
        private const val PAGE_SIZE = 100
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 20000
        private const val PARALLEL_REQUESTS = 2
        private const val TASK_SYNC_TIMEOUT_SECONDS = 90L
        private val apiSlots = Semaphore(PARALLEL_REQUESTS, true)
        private val cooldownUntil = AtomicLong()

        internal fun parseExecution(html: String): String? {
            val inputRegex = Regex("(?i)<input\\b[^>]*>")
            val attributeRegex = Regex("(?i)\\b(name|value)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
            for (inputMatch in inputRegex.findAll(html)) {
                var name: String? = null
                var value: String? = null
                for (attribute in attributeRegex.findAll(inputMatch.value)) {
                    val rawValue = attribute.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: continue
                    when (attribute.groupValues[1].lowercase()) {
                        "name" -> name = decodeHTMLEntities(rawValue)
                        "value" -> value = decodeHTMLEntities(rawValue)
                    }
                }
                if (name == "execution" && !value.isNullOrEmpty()) return value
            }
            return null
        }

        internal fun ticketFrom(location: String): String? = runCatching {
            val uri = URI.create(SERVICE_ORIGIN).resolve(location)
            val effectivePort = if (uri.port == -1) 443 else uri.port
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals(UCLOUD_HOST, ignoreCase = true) ||
                effectivePort != 443 || uri.userInfo != null
            ) {
                return null
            }
            uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { field ->
                val name = URLDecoder.decode(field.substringBefore('='), StandardCharsets.UTF_8.name())
                if (name != "ticket" || !field.contains('=')) return@firstNotNullOfOrNull null
                URLDecoder.decode(field.substringAfter('='), StandardCharsets.UTF_8.name())
                    .takeIf { it.isNotEmpty() }
            }
        }.getOrNull()

        private fun decodeHTMLEntities(value: String): String = value
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        private fun formBody(vararg fields: Pair<String, String>): String =
            fields.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private class SyncScope {
        private val connections = mutableSetOf<HttpURLConnection>()
        private var closed = false
        @Volatile var unauthorized = false

        @Synchronized
        fun register(connection: HttpURLConnection) {
            if (closed || Thread.currentThread().isInterrupted) throw InterruptedException()
            connections.add(connection)
        }

        @Synchronized
        fun release(connection: HttpURLConnection) {
            connections.remove(connection)
        }

        fun cancel() {
            val open = synchronized(this) {
                closed = true
                connections.toList()
            }
            open.forEach { runCatching { it.disconnect() } }
        }
    }

    private val syncScope = ThreadLocal<SyncScope>()

    private fun checkApiCooldown() {
        if (System.currentTimeMillis() < cooldownUntil.get()) {
            throw ScheduleException(ScheduleFailureKind.HTTP_FAILED, "教学云请求受限，请稍后刷新。", statusCode = 429)
        }
    }

    private fun requireTrustedHTTPS(uri: URI, expectedHost: String) {
        val effectivePort = if (uri.port == -1) 443 else uri.port
        val allowedHosts = setOf(AUTH_HOST, UCLOUD_HOST, API_HOST)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(expectedHost, ignoreCase = true) ||
            !allowedHosts.contains(uri.host.lowercase()) ||
            effectivePort != 443 || uri.userInfo != null
        ) {
            throw ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, "\u6559\u5b66\u4e91\u63a5\u53e3\u5730\u5740\u4e0d\u53d7\u4fe1\u4efb\u3002")
        }
    }

    private fun apiHeaders(accessToken: String?): Map<String, String> = buildMap {
        put("Accept", "application/json, text/plain, */*")
        put("Authorization", PORTAL_AUTHORIZATION)
        put("Tenant-Id", TENANT_ID)
        put("Referer", "$SERVICE_ORIGIN/")
        if (accessToken != null) put("Blade-Auth", accessToken)
    }

    private fun execute(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: String?,
        maximumBytes: Int,
        expectedHost: String,
        acceptedStatus: IntRange,
    ): UCloudHttpResult {
        requireTrustedHTTPS(uri, expectedHost)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val scope = syncScope.get()
        val isApi = expectedHost == API_HOST
        if (isApi) apiSlots.acquire()
        try {
            if (isApi) checkApiCooldown()
            if (scope?.unauthorized == true) {
                throw ScheduleException(ScheduleFailureKind.HTTP_FAILED, "教学云会话已失效，请重新刷新。", statusCode = 401)
            }
            return executeRequest(uri, method, headers, body, maximumBytes, acceptedStatus, scope, isApi)
        } finally {
            if (isApi) apiSlots.release()
        }
    }

    private fun executeRequest(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: String?,
        maximumBytes: Int,
        acceptedStatus: IntRange,
        scope: SyncScope?,
        isApi: Boolean,
    ): UCloudHttpResult {
        val connection = openConnection(uri)
        try {
            scope?.register(connection)
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "BUPT-Class-Schedule/1.0 Android")
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            if (body != null) {
                connection.doOutput = true
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                try {
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                } finally {
                    bytes.fill(0)
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val status = connection.responseCode
            if (status !in acceptedStatus) {
                if (status == 401) scope?.unauthorized = true
                val retryAfter = connection.getHeaderField("Retry-After")
                if (isApi && (status == 429 || (status == 503 && retryAfter != null))) {
                    val now = System.currentTimeMillis()
                    val seconds = retryAfter?.trim()?.toLongOrNull()
                    val date = retryAfter?.let {
                        runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(it)?.time }.getOrNull()
                    }
                    val until = when {
                        seconds != null -> now + seconds.coerceIn(1, (Long.MAX_VALUE - now) / 1000) * 1000
                        date != null && date > now -> date
                        else -> now + 30000
                    }
                    synchronized(cooldownUntil) {
                        cooldownUntil.set(maxOf(cooldownUntil.get(), until))
                    }
                }
                throw ScheduleException(ScheduleFailureKind.HTTP_FAILED, "教学云请求失败（HTTP $status）。", statusCode = status)
            }
            val declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (declaredLength > maximumBytes) {
                throw ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, "\u6559\u5b66\u4e91\u63a5\u53e3\u54cd\u5e94\u8fc7\u5927\u3002")
            }
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            if (stream != null) {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                stream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (output.size() + count > maximumBytes) {
                            throw ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, "\u6559\u5b66\u4e91\u63a5\u53e3\u54cd\u5e94\u8fc7\u5927\u3002")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val headerFields = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to (it.value ?: emptyList()) }
            return UCloudHttpResult(
                status = status,
                headers = headerFields,
                body = output.toString(StandardCharsets.UTF_8.name()),
            )
        } catch (error: ScheduleException) {
            throw error
        } catch (error: IOException) {
            throw ScheduleException(
                ScheduleFailureKind.NETWORK_UNREACHABLE,
                "\u65e0\u6cd5\u8fde\u63a5\u6559\u5b66\u4e91\uff0c\u8bf7\u68c0\u67e5\u5f53\u524d\u7f51\u7edc\u540e\u91cd\u8bd5\u3002",
                retryable = true,
                cause = error,
            )
        } finally {
            scope?.release(connection)
            connection.disconnect()
        }
    }

    protected open fun openConnection(uri: URI): HttpURLConnection =
        uri.toURL().openConnection() as HttpURLConnection

    open fun login(credentials: Credentials): UCloudSession {
        val account = credentials.account.trim()
        val password = credentials.teachingCloudPassword.orEmpty()
        if (account.isEmpty() || password.isEmpty()) {
            throw ScheduleException(ScheduleFailureKind.EMPTY_CREDENTIALS, "\u8bf7\u8f93\u5165\u5b66\u53f7\u548c\u4e91\u90ae\u5bc6\u7801\u3002")
        }
        checkApiCooldown()

        val loginPage = execute(
            URI.create(CAS_LOGIN_URL),
            method = "GET",
            headers = mapOf("Accept" to "text/html"),
            body = null,
            maximumBytes = MAXIMUM_LOGIN_BYTES,
            expectedHost = AUTH_HOST,
            acceptedStatus = 200..299,
        )
        val execution = parseExecution(loginPage.body)
            ?: throw ScheduleException(ScheduleFailureKind.LOGIN_FAILED, "\u7edf\u4e00\u8ba4\u8bc1\u767b\u5f55\u9875\u7f3a\u5c11 execution \u53c2\u6570\u3002")

        val cookies = loginPage.headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .map { it.substringBefore(';').trim() }
            .filter(String::isNotEmpty)
            .joinToString("; ")
        if (cookies.isEmpty() || cookies.toByteArray(StandardCharsets.UTF_8).size > MAXIMUM_COOKIE_BYTES) {
            throw ScheduleException(ScheduleFailureKind.LOGIN_FAILED, "\u7edf\u4e00\u8ba4\u8bc1\u672a\u8fd4\u56de\u6709\u6548\u4f1a\u8bdd Cookie\u3002")
        }

        val loginResult = execute(
            URI.create(CAS_LOGIN_URL),
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Cookie" to cookies,
                "Referer" to CAS_LOGIN_URL,
            ),
            body = formBody(
                "username" to account,
                "password" to password,
                "type" to "username_password",
                "execution" to execution,
                "_eventId" to "submit",
            ),
            maximumBytes = MAXIMUM_LOGIN_BYTES,
            expectedHost = AUTH_HOST,
            acceptedStatus = 200..399,
        )
        val location = loginResult.headers.entries
            .firstOrNull { it.key.equals("Location", ignoreCase = true) }
            ?.value?.firstOrNull()
            .orEmpty()
        val ticket = ticketFrom(location)
            ?: throw ScheduleException(
                ScheduleFailureKind.LOGIN_FAILED,
                "\u7edf\u4e00\u8ba4\u8bc1\u672a\u8fd4\u56de\u6709\u6548\u7968\u636e\uff0c\u8bf7\u68c0\u67e5\u6559\u5b66\u4e91\u5bc6\u7801\u6216\u5148\u5728\u7f51\u9875\u7aef\u5b8c\u6210\u9a8c\u8bc1\u3002",
            )

        val tokenResult = execute(
            URI.create(API_ORIGIN).resolve("/ykt-basics/oauth/token"),
            method = "POST",
            headers = apiHeaders(null) + ("Content-Type" to "application/x-www-form-urlencoded"),
            body = formBody("ticket" to ticket, "grant_type" to "third"),
            maximumBytes = MAXIMUM_TOKEN_BYTES,
            expectedHost = API_HOST,
            acceptedStatus = 200..299,
        )
        val tokenRoot = runCatching { JSONObject(tokenResult.body) }
            .getOrElse { throw ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, "\u6559\u5b66\u4e91\u4ee4\u724c\u6570\u636e\u683c\u5f0f\u5f02\u5e38\u3002") }
        if (tokenRoot.has("error")) {
            val err = tokenRoot.optString("error_description").ifEmpty { tokenRoot.optString("error") }
            val safeErr = UCloudAssignmentParser.sanitizeErrorMessage(err)
            throw ScheduleException(ScheduleFailureKind.LOGIN_FAILED, "\u6559\u5b66\u4e91\u8ba4\u8bc1\u5931\u8d25: $safeErr")
        }
        if (tokenRoot.has("code") && tokenRoot.optInt("code") != 200) {
            val msg = tokenRoot.optString("msg").ifEmpty { "\u72b6\u6001\u7801 ${tokenRoot.optInt("code")}" }
            val safeMsg = UCloudAssignmentParser.sanitizeErrorMessage(msg)
            throw ScheduleException(ScheduleFailureKind.LOGIN_FAILED, "\u6559\u5b66\u4e91\u8ba4\u8bc1\u5931\u8d25: $safeMsg")
        }
        val accessToken = tokenRoot.optString("access_token").trim()
            .ifEmpty { throw ScheduleException(ScheduleFailureKind.MISSING_TOKEN, "\u6559\u5b66\u4e91\u672a\u8fd4\u56de\u8bbf\u95ee\u4ee4\u724c\u3002") }
        val userId = (tokenRoot.optString("user_id").takeIf(String::isNotEmpty)
            ?: tokenRoot.optString("userId")).trim()
            .ifEmpty { throw ScheduleException(ScheduleFailureKind.MISSING_TOKEN, "\u6559\u5b66\u4e91\u672a\u8fd4\u56de\u7528\u6237\u6807\u8bc6\u3002") }
        return UCloudSession(accessToken = accessToken, userId = userId)
    }

    private fun <T : Any> fetchPages(
        request: (Int) -> JSONObject,
        parse: (JSONObject) -> T?,
    ): List<T> {
        val items = mutableListOf<T>()
        var expectedPages = 0
        var seenRecords = 0L
        var declaredTotal: Int? = null
        var pageSize = PAGE_SIZE
        var current = 1
        do {
            val page = UCloudAssignmentParser.parseAssignmentPage(request(current), current, pageSize)
            if (current == 1) {
                expectedPages = page.pages
                declaredTotal = page.reportedTotal
                if (page.records.isNotEmpty()) pageSize = page.records.size
            }
            if (page.current != current || page.pages != expectedPages || page.pages > MAX_PAGES ||
                (current < expectedPages && page.records.isEmpty()) ||
                (declaredTotal != null && page.reportedTotal != null && page.reportedTotal != declaredTotal)
            ) UCloudAssignmentParser.invalidPage()
            seenRecords += page.records.size
            page.records.mapNotNullTo(items, parse)
            current++
        } while (current <= expectedPages)
        if (declaredTotal != null && seenRecords < declaredTotal) UCloudAssignmentParser.invalidPage()
        return items
    }

    open fun fetchCourses(session: UCloudSession): List<UCloudCourseRecord> =
        fetchPages({ current ->
            val query = "current=$current&size=$PAGE_SIZE&userId=${encode(session.userId)}&siteRoleCode=2"
            getTaskPage(session, "/ykt-site/site/list/student/current?$query", "课程")
        }, UCloudAssignmentParser::parseCourseRecord).distinctBy { it.siteId }

    open fun fetchAssignmentsForCourse(
        session: UCloudSession,
        course: UCloudCourseRecord,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> = fetchPages({ current ->
        val body = JSONObject()
            .put("siteId", course.siteId)
            .put("userId", session.userId)
            .put("keyword", "")
            .put("current", current)
            .put("size", PAGE_SIZE)
            .put("studentAssignmentStatus", JSONObject.NULL)
            .put("status", 0)
            .put("sortColumn", "")
            .put("sortType", JSONObject.NULL)
        val uri = URI.create(API_ORIGIN).resolve("/ykt-site/work/student/list")
        val result = execute(uri, "POST", apiHeaders(session.accessToken) + ("Content-Type" to "application/json"),
            body.toString(), MAXIMUM_API_BYTES, API_HOST, 200..299)
        UCloudAssignmentParser.parseApiRoot(result.body, "作业")
    }) { record ->
        UCloudAssignmentParser.parseAssignment(record, course.siteId, course.siteName, termStartDate, nowCalendar)
    }

    open fun fetchQuizzesForCourse(
        session: UCloudSession,
        course: UCloudCourseRecord,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> = fetchPages({ current ->
        val query = "current=$current&size=$PAGE_SIZE&status=-1&siteId=${encode(course.siteId)}&statusSelf=${encode("全部")}"
        getTaskPage(session, "/ykt-site/examination/list-stu?$query", "测验")
    }) { record ->
        UCloudAssignmentParser.parseQuiz(record, course.siteId, course.siteName, termStartDate, nowCalendar)
    }

    open fun fetchSurveysForCourse(
        session: UCloudSession,
        course: UCloudCourseRecord,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> = fetchPages({ current ->
        val query = "level=1&userId=${encode(session.userId)}&siteId=${encode(course.siteId)}&current=$current&size=$PAGE_SIZE"
        getTaskPage(session, "/ykt-activity/survey/page/todo?$query", "问卷")
    }) { record ->
        UCloudAssignmentParser.parseSurvey(record, course.siteId, course.siteName, termStartDate, nowCalendar)
    }

    private fun getTaskPage(session: UCloudSession, path: String, action: String): JSONObject {
        val result = execute(URI.create(API_ORIGIN).resolve(path), "GET", apiHeaders(session.accessToken),
            null, MAXIMUM_API_BYTES, API_HOST, 200..299)
        return UCloudAssignmentParser.parseApiRoot(result.body, action)
    }

    private data class Source(val course: UCloudCourseRecord, val type: TaskType)
    private data class SourceResult(val index: Int, val items: List<Assignment>? = null, val error: Exception? = null)

    open fun fetchAll(
        session: UCloudSession,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): UCloudSyncResult {
        val courses = fetchCourses(session)
        val sources = courses.flatMap { course -> TaskType.entries.map { Source(course, it) } }
        val outcomes = arrayOfNulls<SourceResult>(sources.size)
        if (sources.isNotEmpty()) {
            val scope = SyncScope()
            val pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
            val completed = ExecutorCompletionService<SourceResult>(pool)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TASK_SYNC_TIMEOUT_SECONDS)
            var next = 0

            fun enqueue(index: Int) {
                val (course, type) = sources[index]
                val now = nowCalendar.clone() as Calendar
                completed.submit {
                    syncScope.set(scope)
                    try {
                        val items = when (type) {
                            TaskType.ASSIGNMENT -> fetchAssignmentsForCourse(session, course, termStartDate, now)
                            TaskType.QUIZ -> fetchQuizzesForCourse(session, course, termStartDate, now)
                            TaskType.SURVEY -> fetchSurveysForCourse(session, course, termStartDate, now)
                        }
                        SourceResult(index, items)
                    } catch (error: Exception) {
                        SourceResult(index, error = error)
                    } finally {
                        syncScope.remove()
                    }
                }
            }

            try {
                repeat(minOf(PARALLEL_REQUESTS, sources.size)) { enqueue(next++) }
                for (ignored in sources.indices) {
                    val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
                    val finished = completed.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    val result = finished.get()
                    outcomes[result.index] = result
                    if (next < sources.size && System.nanoTime() < deadline) enqueue(next++)
                }
            } finally {
                pool.shutdownNow()
                scope.cancel()
            }
        }

        val successful = TaskType.entries.associateWith { mutableSetOf<String>() }
        val allItems = mutableListOf<Assignment>()
        for ((index, source) in sources.withIndex()) {
            val items = outcomes[index]?.items ?: continue
            successful.getValue(source.type).add(source.course.siteId)
            allItems.addAll(items)
        }
        if (sources.isNotEmpty() && successful.values.all { it.isEmpty() }) {
            throw outcomes.firstNotNullOfOrNull { it?.error }
                ?: ScheduleException(ScheduleFailureKind.NETWORK_UNREACHABLE, "教学云任务查询超时，请稍后刷新。")
        }

        val successfulSites = courses.map { it.siteId }.filterTo(mutableSetOf()) { siteId ->
            successful.values.all { siteId in it }
        }
        val failedSites = courses.map { it.siteId }.toSet() - successfulSites
        return UCloudSyncResult(
            assignments = UCloudAssignmentParser.mergeAndSort(allItems, MAXIMUM_ASSIGNMENTS),
            successfulSiteIds = successfulSites,
            successfulAssignmentSiteIds = successful.getValue(TaskType.ASSIGNMENT),
            successfulQuizSiteIds = successful.getValue(TaskType.QUIZ),
            successfulSurveySiteIds = successful.getValue(TaskType.SURVEY),
            failedSiteIds = failedSites,
            allCoursesSucceeded = failedSites.isEmpty(),
        )
    }


    open fun fetchAll(
        credentials: Credentials,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): UCloudSyncResult {
        val session = login(credentials)
        return fetchAll(session, termStartDate, nowCalendar)
    }

    open fun fetchDetail(session: UCloudSession, assignmentId: String): String {
        val uri = URI.create(API_ORIGIN).resolve("/ykt-site/work/detail?assignmentId=${encode(assignmentId)}")
        val result = execute(
            uri,
            method = "GET",
            headers = apiHeaders(session.accessToken),
            body = null,
            maximumBytes = MAXIMUM_API_BYTES,
            expectedHost = API_HOST,
            acceptedStatus = 200..299,
        )
        val root = UCloudAssignmentParser.parseApiRoot(result.body, "\u4f5c\u4e1a\u8be6\u60c5")
        return UCloudAssignmentParser.parseDetailContent(root)
    }
}
