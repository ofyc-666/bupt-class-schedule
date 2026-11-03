package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.logic.ScheduleLogic
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.TaskType
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import com.bupt.schedule.ui.model.CourseTone
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class UCloudCourseRecord(
    val siteId: String,
    val siteName: String,
)

data class UCloudAssignmentPage(
    val records: List<JSONObject>,
    val total: Int,
    val current: Int,
    val pages: Int,
    val reportedTotal: Int? = null,
)

data class UCloudSyncResult(
    val assignments: List<Assignment>,
    val successfulSiteIds: Set<String>,
    val failedSiteIds: Set<String>,
    val allCoursesSucceeded: Boolean,
    val successfulAssignmentSiteIds: Set<String> = successfulSiteIds,
    val successfulQuizSiteIds: Set<String> = successfulSiteIds,
    val successfulSurveySiteIds: Set<String> = emptySet(),
) {
    fun successfulSites(type: TaskType): Set<String> = when (type) {
        TaskType.ASSIGNMENT -> successfulAssignmentSiteIds
        TaskType.QUIZ -> successfulQuizSiteIds
        TaskType.SURVEY -> successfulSurveySiteIds
    }
}

object UCloudAssignmentParser {
    val SHANGHAI: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    fun parseApiRoot(body: String, actionName: String = "\u63a5\u53e3"): JSONObject {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw ScheduleException(
                ScheduleFailureKind.INVALID_RESPONSE,
                "\u6559\u5b66\u4e91${actionName}\u6570\u636e\u683c\u5f0f\u5f02\u5e38\u3002",
            )
        }
        if (root.has("code")) {
            val code = root.optInt("code", -1)
            if (code != 200) {
                val rawMsg = root.optString("msg").takeIf { it.isNotBlank() }
                    ?: root.optString("message").takeIf { it.isNotBlank() }
                    ?: "\u4e1a\u52a1\u72b6\u6001\u7801\u5f02\u5e38 ($code)"
                val safeMsg = sanitizeErrorMessage(rawMsg)
                throw ScheduleException(
                    ScheduleFailureKind.INVALID_RESPONSE,
                    "\u6559\u5b66\u4e91${actionName}\u8bf7\u6c42\u5931\u8d25: $safeMsg",
                )
            }
        }
        if (root.has("success") && !root.optBoolean("success", true)) {
            val rawMsg = root.optString("msg").takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
                ?: "\u64cd\u4f5c\u672a\u6210\u529f"
            val safeMsg = sanitizeErrorMessage(rawMsg)
            throw ScheduleException(
                ScheduleFailureKind.INVALID_RESPONSE,
                "\u6559\u5b66\u4e91${actionName}\u8bf7\u6c42\u5931\u8d25: $safeMsg",
            )
        }
        return root
    }

    fun sanitizeErrorMessage(msg: String): String {
        var sanitized = msg
        val sensitivePatterns = listOf(
            Regex("(?i)bearer\\s+[a-zA-Z0-9_.-]+"),
            Regex("(?i)token\\s*[:=]\\s*[a-zA-Z0-9_.-]+"),
            Regex("(?i)ticket\\s*[:=]\\s*[a-zA-Z0-9_.-]+"),
            Regex("(?i)password\\s*[:=]\\s*[^&\\s]+"),
            Regex("(?i)cookie\\s*[:=]\\s*[^;\\s]+"),
        )
        for (p in sensitivePatterns) {
            sanitized = sanitized.replace(p, "[PROTECTED]")
        }
        return sanitized.take(100)
    }

    fun parseCourseRecords(root: JSONObject): List<UCloudCourseRecord> =
        parseAssignmentPage(root).records.map(::parseCourseRecord)

    internal fun parseCourseRecord(record: JSONObject): UCloudCourseRecord {
        val siteId = firstString(record, "id", "siteId", "courseId") ?: invalidPage()
        val siteName = firstString(record, "siteName", "courseName", "siteTitle", "name") ?: "未命名课程"
        return UCloudCourseRecord(siteId = siteId, siteName = siteName)
    }

    fun parseAssignmentPage(root: JSONObject, requestedPage: Int = 1, pageSize: Int = 100): UCloudAssignmentPage {
        val outer = root.optJSONObject("data")
        val data = listOfNotNull(outer?.optJSONObject("data"), outer, root)
            .firstOrNull { it.has("records") } ?: invalidPage()
        val recordsArray = data.optJSONArray("records") ?: invalidPage()
        val records = (0 until recordsArray.length()).map { recordsArray.optJSONObject(it) ?: invalidPage() }
        val total = if (data.has("total")) data.optInt("total", -1).takeIf { it >= 0 } ?: invalidPage() else null
        val current = data.optInt("current", if (data.has("current")) -1 else requestedPage)
        val pages = if (data.has("pages")) {
            data.optInt("pages", -1)
        } else {
            val fallback = if (requestedPage == 1 && records.isNotEmpty()) records.size else pageSize
            val size = data.optInt("size", fallback).takeIf { it > 0 }
            when {
                total == 0 -> 0
                total != null && size != null -> ((total.toLong() + size - 1) / size).toInt()
                else -> invalidPage()
            }
        }
        if (current <= 0 || pages < 0 || (pages == 0 && (records.isNotEmpty() || (total ?: 0) > 0))) invalidPage()
        return UCloudAssignmentPage(
            records = records,
            total = total ?: records.size,
            current = current,
            pages = pages.coerceAtLeast(1),
            reportedTotal = total,
        )
    }

    internal fun invalidPage(): Nothing =
        throw ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, "教学云分页响应不完整或格式异常。")

    fun parseAssignment(
        raw: JSONObject,
        siteId: String,
        siteName: String,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(SHANGHAI),
    ): Assignment? {
        val statusVal = raw.opt("assignmentStatus")
        val assignmentStatus = when (statusVal) {
            is Number -> statusVal.toInt()
            is String -> statusVal.trim().toIntOrNull()
            else -> null
        }
        // 核心业务规则：只有 assignmentStatus == 99 的记录进入 App，其余一律丢弃
        if (assignmentStatus != 99) {
            return null
        }

        val id = firstString(raw, "id", "assignmentId") ?: return null
        val title = firstString(raw, "assignmentTitle", "title").orEmpty().trim().ifEmpty { "未命名作业" }
        val chapterName = firstString(raw, "chapterName")?.trim()?.takeIf { it.isNotEmpty() }

        val endTimeRaw = firstString(raw, "assignmentEndTime", "endTime").orEmpty().trim()
        return buildTask(id, title, chapterName, endTimeRaw, siteId, siteName, termStartDate, nowCalendar, TaskType.ASSIGNMENT)
    }

    private fun buildTask(
        id: String,
        title: String,
        chapterName: String?,
        endTimeRaw: String,
        siteId: String,
        siteName: String,
        termStartDate: String?,
        nowCalendar: Calendar,
        taskType: TaskType,
        description: String = "",
    ): Assignment? {
        val deadlineCal = parseDeadlineDate(endTimeRaw) ?: return null
        val isOverdue = taskType.isOverdue(deadlineCal.timeInMillis, nowCalendar.timeInMillis)
        val status = if (isOverdue) AssignmentStatus.OVERDUE else AssignmentStatus.PENDING

        val deadlineTimeShort = formatTimeShort(deadlineCal)
        val deadlineText = formatDeadlineText(deadlineCal, nowCalendar)
        val remainingDaysText = formatRemainingDaysText(deadlineCal, nowCalendar, isOverdue)

        val weekday = ((deadlineCal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val deadlineSlot = mapTimeToSlot(deadlineTimeShort)

        val week = if (!termStartDate.isNullOrBlank()) {
            val termStartCal = ScheduleLogic.parseContractDate(termStartDate)
            if (termStartCal != null) {
                ScheduleLogic.weekNumber(termStartCal, deadlineCal).coerceAtLeast(0)
            } else {
                0
            }
        } else {
            0
        }

        val tones = CourseTone.entries
        val tone = tones[Math.floorMod(siteId.hashCode(), tones.size)]

        return Assignment(
            id = id,
            courseName = siteName,
            courseId = siteId,
            title = title,
            deadlineText = deadlineText,
            deadlineTimeShort = deadlineTimeShort,
            status = status,
            week = week,
            weekday = weekday,
            deadlineSlot = deadlineSlot,
            description = description,
            score = null,
            remainingDaysText = remainingDaysText,
            tone = tone,
            chapterName = chapterName,
            rawDeadline = endTimeRaw,
            taskType = taskType,
        )
    }

    fun parseQuiz(
        raw: JSONObject,
        siteId: String,
        siteName: String,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(SHANGHAI),
    ): Assignment? {
        if (raw.optString("statusSelf") != "未提交") return null
        val id = firstString(raw, "id") ?: return null
        val deadline = firstString(raw, "endAt") ?: return null
        return buildTask(
            "QUIZ:$id", firstString(raw, "title")?.trim() ?: "未命名测验", null, deadline.trim(),
            siteId, siteName, termStartDate, nowCalendar, TaskType.QUIZ,
        )
    }

    fun parseSurvey(
        raw: JSONObject,
        siteId: String,
        siteName: String,
        termStartDate: String?,
        nowCalendar: Calendar = Calendar.getInstance(SHANGHAI),
    ): Assignment {
        // The student course page renders this endpoint as the list of surveys to participate in.
        // The separate completed list is never fetched. Deadline only controls the display status.
        val id = firstString(raw, "id") ?: invalidPage()
        val deadline = firstString(raw, "endTime")?.trim() ?: invalidPage()
        val responseSite = firstString(raw, "siteId")
        if (responseSite != null && responseSite != siteId) invalidPage()
        val chapter = listOfNotNull(firstString(raw, "partName"), firstString(raw, "nodeName"))
            .joinToString(" ").ifBlank { null }
        return buildTask(
            "SURVEY:$id", firstString(raw, "theme")?.trim() ?: "未命名问卷", chapter, deadline,
            siteId, siteName, termStartDate, nowCalendar, TaskType.SURVEY,
            cleanHtml(firstString(raw, "description").orEmpty()),
        ) ?: invalidPage()
    }

    fun parseDeadlineDate(value: String): Calendar? {
        if (value.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            try {
                val df = SimpleDateFormat(pattern, Locale.US)
                df.timeZone = SHANGHAI
                df.isLenient = false
                val parsed = df.parse(value)
                if (parsed != null) {
                    val cal = Calendar.getInstance(SHANGHAI)
                    cal.time = parsed
                    return cal
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun mapTimeToSlot(timeStr: String): Int {
        val slots = ScheduleLogic.DEFAULT_SLOT_TIMES
        if (timeStr < slots[0].first) return 0
        if (timeStr > slots.last().second) return slots.lastIndex
        for (i in slots.indices) {
            if (timeStr <= slots[i].second) return i
        }
        return slots.lastIndex
    }

    fun parseDetailContent(root: JSONObject): String {
        val data = root.optJSONObject("data") ?: root
        val rawHtml = firstString(data, "assignmentContent", "content").orEmpty()
        return cleanHtml(rawHtml)
    }

    fun cleanHtml(html: String): String {
        if (html.isBlank()) return ""
        val withoutScripts = html.replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")

        val nl = "\n"
        val withNewlines = withoutScripts
            .replace(Regex("(?i)<br\\s*/?>"), nl)
            .replace(Regex("(?i)</p\\s*>"), nl + nl)
            .replace(Regex("(?i)</div\\s*>"), nl)
            .replace(Regex("(?i)<li\\s*>"), "• ")
            .replace(Regex("(?i)</li\\s*>"), nl)

        val stripped = withNewlines.replace(Regex("<[^>]+>"), "")
        val decoded = stripped
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        return decoded.lines()
            .map { it.trimEnd() }
            .joinToString(nl)
            .replace(Regex("\n{3,}"), nl + nl)
            .trim()
    }

    fun formatTimeShort(cal: Calendar): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = SHANGHAI }.format(cal.time)

    fun formatDeadlineText(deadline: Calendar, now: Calendar): String {
        val deadlineCal = (deadline.clone() as Calendar).apply { timeZone = SHANGHAI }
        val nowCal = (now.clone() as Calendar).apply { timeZone = SHANGHAI }
        val isSameYear = deadlineCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val pattern = if (isSameYear) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        return SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = SHANGHAI }.format(deadlineCal.time)
    }

    fun formatRemainingDaysText(deadline: Calendar, now: Calendar, isOverdue: Boolean): String {
        if (isOverdue) return "\u5df2\u622a\u6b62"
        val diffMillis = deadline.timeInMillis - now.timeInMillis
        if (diffMillis <= 0) return "\u5373\u5c06\u622a\u6b62"

        val diffHours = diffMillis / 3600000L
        val diffDays = ((diffMillis + 86400000L - 1) / 86400000L).toInt()

        return when {
            diffDays > 1 -> "\u5269\u4f59 " + diffDays + " \u5929"
            diffHours >= 1 -> "\u5269\u4f59 " + diffHours + " \u5c0f\u65f6"
            else -> "\u5373\u5c06\u622a\u6b62"
        }
    }

    private fun firstString(source: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            val value = source.opt(key)
            when (value) {
                null, JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotBlank() }
                is Number -> value.toString()
                else -> null
            }
        }

    fun mergeAndSort(items: List<Assignment>, maximumAssignments: Int = 5000): List<Assignment> {
        val distinct = items.distinctBy { it.sourceKey }
        if (distinct.size > maximumAssignments) {
            throw ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, "教学云任务数量超过安全上限。")
        }
        return distinct.sortedWith(
            compareBy<Assignment>(
                { if (it.status == AssignmentStatus.PENDING) 0 else 1 },
                { it.week },
                { it.weekday },
                { it.deadlineSlot },
                { it.deadlineTimeShort },
                { it.courseName },
                { it.title },
                { it.id },
            )
        )
    }
}
