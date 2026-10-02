// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study UCloudAssignmentClient.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar

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
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
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
            val status = connection.responseCode
            if (status !in acceptedStatus) {
                throw ScheduleException(ScheduleFailureKind.HTTP_FAILED, "\u6559\u5b66\u4e91\u8bf7\u6c42\u5931\u8d25\uff08HTTP $status\uff09\u3002")
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
            connection.disconnect()
        }
    }

    open fun login(credentials: Credentials): UCloudSession {
        val account = credentials.account.trim()
        val password = credentials.teachingCloudPassword.orEmpty()
        if (account.isEmpty() || password.isEmpty()) {
            throw ScheduleException(ScheduleFailureKind.EMPTY_CREDENTIALS, "\u8bf7\u8f93\u5165\u5b66\u53f7\u548c\u4e91\u90ae\u5bc6\u7801\u3002")
        }

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

    open fun fetchCourses(session: UCloudSession): List<UCloudCourseRecord> {
        val courses = mutableListOf<UCloudCourseRecord>()
        var currentPage = 1
        var totalPages = 1
        while (currentPage <= totalPages && currentPage <= MAX_PAGES) {
            val query = mapOf(
                "current" to currentPage.toString(),
                "size" to PAGE_SIZE.toString(),
                "userId" to session.userId,
                "siteRoleCode" to "2",
            )
            val queryText = query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            val uri = URI.create(API_ORIGIN).resolve("/ykt-site/site/list/student/current?$queryText")
            val result = execute(
                uri,
                method = "GET",
                headers = apiHeaders(session.accessToken),
                body = null,
                maximumBytes = MAXIMUM_API_BYTES,
                expectedHost = API_HOST,
                acceptedStatus = 200..299,
            )
            val root = UCloudAssignmentParser.parseApiRoot(result.body, "\u8bfe\u7a0b")
            val pageRecords = UCloudAssignmentParser.parseCourseRecords(root)
            courses.addAll(pageRecords)
            val pageInfo = UCloudAssignmentParser.parseAssignmentPage(root)
            totalPages = pageInfo.pages
            if (totalPages > MAX_PAGES) throw ScheduleException(
                ScheduleFailureKind.INVALID_RESPONSE, "课程列表分页超过安全上限。")
            if (currentPage >= totalPages) break
            currentPage++
        }
        return courses.distinctBy { it.siteId }
    }

    open fun fetchAssignmentsForCourse(
        session: UCloudSession,
        course: UCloudCourseRecord,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        var currentPage = 1
        var totalPages = 1
        while (currentPage <= totalPages && currentPage <= MAX_PAGES) {
            val body = JSONObject()
                .put("siteId", course.siteId)
                .put("userId", session.userId)
                .put("keyword", "")
                .put("current", currentPage)
                .put("size", PAGE_SIZE)
                .put("studentAssignmentStatus", JSONObject.NULL)
                .put("status", 0)
                .put("sortColumn", "")
                .put("sortType", JSONObject.NULL)

            val uri = URI.create(API_ORIGIN).resolve("/ykt-site/work/student/list")
            val result = execute(
                uri,
                method = "POST",
                headers = apiHeaders(session.accessToken) + ("Content-Type" to "application/json"),
                body = body.toString(),
                maximumBytes = MAXIMUM_API_BYTES,
                expectedHost = API_HOST,
                acceptedStatus = 200..299,
            )
            val root = UCloudAssignmentParser.parseApiRoot(result.body, "\u4f5c\u4e1a")
            val page = UCloudAssignmentParser.parseAssignmentPage(root)
            totalPages = page.pages
            if (totalPages > MAX_PAGES) throw ScheduleException(
                ScheduleFailureKind.INVALID_RESPONSE, "课程作业分页超过安全上限。")
            for (record in page.records) {
                val item = UCloudAssignmentParser.parseAssignment(
                    raw = record,
                    siteId = course.siteId,
                    siteName = course.siteName,
                    termStartDate = termStartDate,
                    nowCalendar = nowCalendar,
                )
                if (item != null) {
                    assignments.add(item)
                }
            }
            if (page.records.isEmpty() || currentPage >= totalPages) break
            currentPage++
        }
        return assignments
    }

    open fun fetchQuizzesForCourse(
        session: UCloudSession,
        course: UCloudCourseRecord,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> {
        val quizzes = mutableListOf<Assignment>()
        var currentPage = 1
        var totalPages = 1
        while (currentPage <= totalPages && currentPage <= MAX_PAGES) {
            val uri = URI.create(API_ORIGIN).resolve(
                "/ykt-site/examination/list-stu?current=$currentPage&size=$PAGE_SIZE&status=-1&siteId=${encode(course.siteId)}&statusSelf=${encode("全部")}",
            )
            val result = execute(uri, "GET", apiHeaders(session.accessToken), null,
                MAXIMUM_API_BYTES, API_HOST, 200..299)
            val root = UCloudAssignmentParser.parseApiRoot(result.body, "测验")
            val page = UCloudAssignmentParser.parseAssignmentPage(root)
            totalPages = page.pages
            if (totalPages > MAX_PAGES) throw ScheduleException(
                ScheduleFailureKind.INVALID_RESPONSE, "课程测验分页超过安全上限。")
            for (record in page.records) {
                UCloudAssignmentParser.parseQuiz(record, course.siteId, course.siteName,
                    termStartDate, nowCalendar)?.let { quizzes.add(it) }
            }
            if (currentPage >= totalPages) break
            currentPage++
        }
        return quizzes
    }

    open fun fetchAll(
        session: UCloudSession,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): UCloudSyncResult {
        val courses = fetchCourses(session)
        val allItems = mutableListOf<Assignment>()
        val successfulSiteIds = mutableSetOf<String>()
        val successfulAssignmentSiteIds = mutableSetOf<String>()
        val successfulQuizSiteIds = mutableSetOf<String>()
        val failedSiteIds = mutableSetOf<String>()
        var firstError: Exception? = null

        for (course in courses) {
            try {
                val assignments = fetchAssignmentsForCourse(session, course, termStartDate, nowCalendar)
                allItems.addAll(assignments)
                successfulAssignmentSiteIds.add(course.siteId)
            } catch (error: Exception) {
                failedSiteIds.add(course.siteId)
                if (firstError == null) firstError = error
            }
            try {
                val quizzes = fetchQuizzesForCourse(session, course, termStartDate, nowCalendar)
                allItems.addAll(quizzes)
                successfulQuizSiteIds.add(course.siteId)
            } catch (error: Exception) {
                failedSiteIds.add(course.siteId)
                if (firstError == null) firstError = error
            }
            if (course.siteId in successfulAssignmentSiteIds && course.siteId in successfulQuizSiteIds) {
                successfulSiteIds.add(course.siteId)
            }
        }

        if (courses.isNotEmpty() && successfulAssignmentSiteIds.isEmpty() && successfulQuizSiteIds.isEmpty()) {
            throw firstError ?: ScheduleException(
                ScheduleFailureKind.HTTP_FAILED,
                "\u6559\u5b66\u4e91\u4f5c\u4e1a\u63a5\u53e3\u6682\u65f6\u4e0d\u53ef\u7528\u3002",
            )
        }

        val sortedAssignments = UCloudAssignmentParser.mergeAndSort(allItems, MAXIMUM_ASSIGNMENTS)
        return UCloudSyncResult(
            assignments = sortedAssignments,
            successfulSiteIds = successfulSiteIds,
            successfulAssignmentSiteIds = successfulAssignmentSiteIds,
            successfulQuizSiteIds = successfulQuizSiteIds,
            failedSiteIds = failedSiteIds,
            allCoursesSucceeded = failedSiteIds.isEmpty(),
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
