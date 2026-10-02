package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.logic.ScheduleLogic
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.TaskType
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import com.bupt.schedule.ui.model.CourseTone
import org.json.JSONArray
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
)

data class UCloudSyncResult(
    val assignments: List<Assignment>,
    val successfulSiteIds: Set<String>,
    val failedSiteIds: Set<String>,
    val allCoursesSucceeded: Boolean,
    val successfulAssignmentSiteIds: Set<String> = successfulSiteIds,
    val successfulQuizSiteIds: Set<String> = successfulSiteIds,
)

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

    fun parseCourseRecords(root: JSONObject): List<UCloudCourseRecord> {
        val firstData = root.optJSONObject("data") ?: root
        val secondData = firstData.optJSONObject("data") ?: firstData
        val records = secondData.optJSONArray("records") ?: JSONArray()
        val result = mutableListOf<UCloudCourseRecord>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val siteId = firstString(record, "id", "siteId", "courseId") ?: continue
            val siteName = firstString(record, "siteName", "courseName", "siteTitle", "name") ?: "未命名课程"
            result.add(UCloudCourseRecord(siteId = siteId, siteName = siteName))
        }
        return result
    }

    fun parseAssignmentPage(root: JSONObject): UCloudAssignmentPage {
        val data = root.optJSONObject("data") ?: root
        val recordsArray = data.optJSONArray("records") ?: JSONArray()
        val records = (0 until recordsArray.length()).mapNotNull { recordsArray.optJSONObject(it) }
        val total = data.optInt("total", records.size)
        val current = data.optInt("current", 1)
        val pages = data.optInt("pages", 1)
        return UCloudAssignmentPage(
            records = records,
            total = total,
            current = current,
            pages = pages,
        )
    }

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
        val deadlineCal = parseDeadlineDate(endTimeRaw) ?: return null

        val isOverdue = deadlineCal.timeInMillis <= nowCalendar.timeInMillis
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
            description = "",
            score = null,
            remainingDaysText = remainingDaysText,
            tone = tone,
            chapterName = chapterName,
            rawDeadline = endTimeRaw,
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
        val mapped = JSONObject()
            .put("id", "QUIZ:$id")
            .put("assignmentTitle", firstString(raw, "title") ?: "未命名测验")
            .put("assignmentStatus", 99)
            .put("assignmentEndTime", deadline)
        val item = parseAssignment(mapped, siteId, siteName, termStartDate, nowCalendar) ?: return null
        val deadlineCal = parseDeadlineDate(deadline) ?: return null
        val overdue = deadlineCal.timeInMillis < nowCalendar.timeInMillis
        return item.copy(
            taskType = TaskType.QUIZ,
            status = if (overdue) AssignmentStatus.OVERDUE else AssignmentStatus.PENDING,
            remainingDaysText = formatRemainingDaysText(deadlineCal, nowCalendar, overdue),
        )
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

    private fun dayDifference(from: Calendar, to: Calendar): Int {
        val fromStart = (from.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val toStart = (to.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return ((toStart.timeInMillis - fromStart.timeInMillis) / 86400000L).toInt()
    }

    fun formatDeadlineText(deadline: Calendar, now: Calendar): String {
        val timeStr = formatTimeShort(deadline)
        val isSameYear = deadline.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val dayDiff = dayDifference(now, deadline)

        if (isSameYear && dayDiff == 0) {
            return "\u4eca\u5929 " + timeStr
        }
        if (isSameYear && dayDiff == 1) {
            return "\u660e\u5929 " + timeStr
        }

        val nowMonday = ScheduleLogic.mondayOfCurrentWeek(now)
        val deadlineMonday = ScheduleLogic.mondayOfCurrentWeek(deadline)
        val weekDiff = ((deadlineMonday.timeInMillis - nowMonday.timeInMillis) / (7 * 86400000L)).toInt()

        val weekdayName = when (((deadline.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1) {
            1 -> "\u5468\u4e00"
            2 -> "\u5468\u4e8c"
            3 -> "\u5468\u4e09"
            4 -> "\u5468\u56db"
            5 -> "\u5468\u4e94"
            6 -> "\u5468\u516d"
            7 -> "\u5468\u65e5"
            else -> ""
        }

        return when {
            weekDiff == 0 -> weekdayName + " " + timeStr
            weekDiff == 1 -> "\u4e0b" + weekdayName + " " + timeStr
            else -> {
                val dateFormat = SimpleDateFormat("M\u6708d\u65e5", Locale.CHINA).apply { timeZone = SHANGHAI }
                dateFormat.format(deadline.time) + " " + timeStr
            }
        }
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
        val distinct = items.take(maximumAssignments).distinctBy { it.id }
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
