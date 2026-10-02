// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study ScheduleClient.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.logic.ScheduleLogic
import com.bupt.schedule.domain.logic.SemesterLogic
import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import com.bupt.schedule.domain.model.ScheduleSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SjdScheduleClient(
    val tokenManager: TokenManager = TokenManager(),
    private val api: SjdApiClient = SjdApiClient(tokenManager),
) {
    fun fetch(credentials: Credentials): ScheduleSnapshot {
        tokenManager.startNewSession { api.login(credentials) }
        val fallback = SemesterLogic.suggest()
        val current = api.postAuthenticated(
            path = "/bjyddx/student/curriculum?week=",
            referer = SjdApiClient.SCHEDULE_REFERER,
            credentialsProvider = { credentials },
        )
        val curriculum = api.postAuthenticated(
            path = "/bjyddx/student/curriculum?week=all",
            referer = SjdApiClient.SCHEDULE_REFERER,
            credentialsProvider = { credentials },
        )
        if (!api.isSuccessful(current) || !api.isSuccessful(curriculum)) {
            throw ScheduleException(ScheduleFailureKind.HTTP_FAILED, "移动教务课表获取失败。")
        }
        return SjdScheduleParser.parse(current, curriculum, fallback.termID, fallback.termStartDate)
    }
}

object SjdScheduleParser {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    fun parse(
        current: JSONObject,
        curriculum: JSONObject,
        fallbackTermID: String,
        fallbackTermStartDate: String,
        fetchedAt: String = timestamp(Date()),
    ): ScheduleSnapshot {
        val currentData = current.optJSONArray("data")
            ?: invalid("移动教务当前周数据格式异常。")
        val curriculumData = curriculum.optJSONArray("data")
            ?: invalid("移动教务整学期数据格式异常。")
        if (currentData.length() == 0 || curriculumData.length() == 0) emptySchedule()
        val currentRoot = currentData.optJSONObject(0)
            ?: invalid("移动教务当前周数据格式异常。")
        val curriculumRoot = curriculumData.optJSONObject(0)
            ?: invalid("移动教务整学期数据格式异常。")
        val topInfo = currentRoot.optJSONArray("topInfo")?.optJSONObject(0)
        val termID = currentRoot.opt("semesterId").stringValue()
            .ifEmpty { currentRoot.opt("xnxq01id").stringValue() }
            .ifEmpty { topInfo?.opt("semesterId").stringValue() }
            .ifEmpty { topInfo?.opt("xnxq01id").stringValue() }
            .ifEmpty { fallbackTermID }
        val termStartDate = inferTermStartDate(currentRoot) ?: fallbackTermStartDate
        val courseTree = curriculumRoot.opt("item").takeUnless { it == null || it == JSONObject.NULL }
            ?: curriculumRoot.opt("courses").takeUnless { it == null || it == JSONObject.NULL }
            ?: invalid("移动教务整学期课表缺少课程字段。")

        val rawCourses = mutableListOf<JSONObject>()
        collectCourses(courseTree, rawCourses)
        if (rawCourses.isEmpty()) emptySchedule()
        val parsed = rawCourses.mapNotNull(::parseCourse)
        if (parsed.isEmpty()) invalid("移动教务课程数据格式异常。")
        val courses = parsed.distinctBy(Course::id)
            .sortedWith(compareBy(Course::weekday, Course::startSlot, Course::name))
        return ScheduleSnapshot(termID, termStartDate, fetchedAt, courses)
    }

    private fun collectCourses(value: Any?, output: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> if (value.has("courseName") || value.has("jx0408id")) {
                output += value
            } else {
                value.keys().forEach { collectCourses(value.opt(it), output) }
            }
            is JSONArray -> (0 until value.length()).forEach { collectCourses(value.opt(it), output) }
        }
    }

    private fun parseCourse(raw: JSONObject): Course? {
        val (startSlot, endSlot) = slots(raw) ?: return null
        val weekday = raw.opt("weekDay").stringValue()
            .ifEmpty { raw.opt("classTime").stringValue() }
            .firstOrNull()?.digitToIntOrNull() ?: return null
        if (weekday !in 1..7) return null

        val name = raw.opt("courseName").stringValue().trim().ifEmpty { "未命名课程" }
        val teacher = raw.opt("teacherName").stringValue().trim()
        val building = raw.opt("buildingName").stringValue().trim()
        val rawRoom = raw.opt("classroomName").stringValue().trim()
            .ifEmpty { raw.opt("location").stringValue().trim() }
        val room = normalizeCourseRoom(rawRoom)
        val location = when {
            building.isNotEmpty() && room.isNotEmpty() && !room.contains(building) -> "$building-$room"
            room.isNotEmpty() -> room
            else -> building
        }
        val weekText = raw.opt("classWeek").stringValue().trim()
            .ifEmpty { raw.opt("classWeekDetails").stringValue().trim() }
        val weekNumbers = weekNumbers(raw)
        val sourceCourseID = raw.opt("jx0408id").stringValue().trim().takeIf(String::isNotEmpty)
        val stable = listOf(
            sourceCourseID.orEmpty(), name, teacher, location, weekText,
            weekday.toString(), startSlot.toString(), endSlot.toString(),
        ).joinToString("|")
        val id = MessageDigest.getInstance("SHA-1")
            .digest(stable.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(12)

        val rawStartTime = raw.opt("startTime").stringValue().trim()
        val rawEndTime = raw.opt("endTIme").stringValue().trim().ifEmpty { raw.opt("endTime").stringValue().trim() }
        val startTime = rawStartTime.ifEmpty { defaultSlotTime(startSlot).first }
        val endTime = rawEndTime.ifEmpty { defaultSlotTime(endSlot).second }
        val teachingClass = raw.opt("ktmc").stringValue().trim()

        return Course(
            id = id,
            name = name,
            teacher = teacher,
            room = location,
            weekText = weekText,
            weekNumbers = weekNumbers,
            weekday = weekday,
            startSlot = startSlot,
            endSlot = endSlot,
            sourceCourseID = sourceCourseID,
            startTime = startTime,
            endTime = endTime,
            teachingClass = teachingClass,
        )
    }

    internal fun normalizeCourseRoom(value: String): String {
        val normalized = value.trim().replace('－', '-').replace('—', '-').replace('–', '-')
        val parts = normalized.split('-')
        if (parts.size != 2) return normalized
        val prefix = parts[0].removePrefix("教")
        return if (
            prefix.length == 1 && prefix.all(Char::isDigit) &&
            parts[1].length == 3 && parts[1].all(Char::isDigit)
        ) parts[1] else normalized
    }

    private fun weekNumbers(raw: JSONObject): List<Int> {
        val explicit = integers(raw.opt("classWeekDetails").stringValue())
        if (explicit.isNotEmpty()) return explicit.distinct().sorted()
        val text = raw.opt("classWeek").stringValue()
            .replace("周", "").replace(" ", "").replace("，", ",")
        val odd = text.contains("单")
        val even = text.contains("双")
        return text.split(',').flatMap { item ->
            val values = integers(item)
            if (values.size >= 2) (values[0]..values[1]).toList() else values
        }.distinct().filter { (!odd || it % 2 == 1) && (!even || it % 2 == 0) }.sorted()
    }

    private fun slots(raw: JSONObject): Pair<Int, Int>? {
        val classTime = raw.opt("classTime").stringValue().drop(1)
        var nodes = Regex("\\d{2}").findAll(classTime).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nodes.isEmpty()) nodes = integers(raw.opt("weekNoteDetail").stringValue())
        val minimum = nodes.minOrNull() ?: return null
        val maximum = nodes.maxOrNull() ?: return null
        if (minimum < 1 || maximum > SLOT_COUNT || minimum > maximum) return null
        return minimum - 1 to maximum - 1
    }

    private fun inferTermStartDate(root: JSONObject): String? {
        val dates = root.optJSONArray("date") ?: return null
        val dated = (0 until dates.length()).mapNotNull(dates::optJSONObject)
            .firstOrNull { it.has("mxrq") && it.opt("zc").stringValue() != "all" }
            ?: return null
        val week = dated.opt("zc").stringValue().toIntOrNull()
            ?: root.opt("week").stringValue().toIntOrNull()
            ?: root.optJSONArray("topInfo")?.optJSONObject(0)?.opt("week").stringValue().toIntOrNull()
            ?: return null
        if (week < 0) return null
        val day = parseDate(dated.opt("mxrq").stringValue()) ?: return null
        val calendarWeekday = ((day.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val rawWeekday = dated.opt("xqid").stringValue().toIntOrNull()
        val weekday = when {
            rawWeekday == 0 -> 7
            rawWeekday != null && rawWeekday in 1..7 -> rawWeekday
            else -> calendarWeekday
        }
        day.add(Calendar.DAY_OF_MONTH, -(weekday - 1) - ((week - 1) * 7))
        return contractDate().format(day.time)
    }

    private fun parseDate(value: String): Calendar? = runCatching {
        Calendar.getInstance(shanghai).apply {
            time = contractDate().parse(value) ?: error("invalid date")
        }
    }.getOrNull()

    private fun integers(value: String) = Regex("\\d+").findAll(value)
        .mapNotNull { it.value.toIntOrNull() }.toList()

    private fun contractDate() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = shanghai
        isLenient = false
    }

    private fun timestamp(date: Date): String {
        val formatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = shanghai
        }.format(date)
        return formatted.dropLast(2) + ":" + formatted.takeLast(2)
    }

    private fun invalid(message: String): Nothing =
        throw ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, message)

    private fun emptySchedule(): Nothing =
        throw ScheduleException(ScheduleFailureKind.EMPTY_SCHEDULE, "移动教务返回的课表为空。")

    private fun defaultSlotTime(slot: Int): Pair<String, String> =
        ScheduleLogic.defaultSlotTime(slot) ?: ("" to "")

    private const val SLOT_COUNT = 14
}
