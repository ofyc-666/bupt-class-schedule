// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study AppModels.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.domain.logic

import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.domain.model.ScheduleSnapshot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object ScheduleLogic {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    val DEFAULT_SLOT_TIMES = listOf(
        "08:00" to "08:45",
        "08:50" to "09:35",
        "09:50" to "10:35",
        "10:40" to "11:25",
        "11:30" to "12:15",
        "13:00" to "13:45",
        "13:50" to "14:35",
        "14:45" to "15:30",
        "15:40" to "16:25",
        "16:35" to "17:20",
        "17:25" to "18:10",
        "18:30" to "19:15",
        "19:20" to "20:05",
        "20:10" to "20:55",
    )

    fun defaultSlotTime(slot: Int): Pair<String, String>? {
        if (slot !in DEFAULT_SLOT_TIMES.indices) return null
        return DEFAULT_SLOT_TIMES[slot]
    }

    fun defaultSlotTimeRange(startSlot: Int, endSlot: Int): Pair<String, String>? {
        if (startSlot !in DEFAULT_SLOT_TIMES.indices || endSlot !in DEFAULT_SLOT_TIMES.indices || startSlot > endSlot) return null
        return DEFAULT_SLOT_TIMES[startSlot].first to DEFAULT_SLOT_TIMES[endSlot].second
    }

    fun formatTeachingClass(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "暂无教学班信息"
        val cleaned = if (trimmed.startsWith("[") && trimmed.contains("]")) {
            val inside = trimmed.substring(1, trimmed.indexOf(']')).trim()
            val after = trimmed.substring(trimmed.indexOf(']') + 1).trim()
            val normalizedRange = inside.replace("-", "–")
            if (after.isNotEmpty()) normalizedRange + " " + after else normalizedRange
        } else {
            trimmed.removeSurrounding("[", "]").trim()
        }
        return cleaned.ifEmpty { "暂无教学班信息" }
    }

    fun currentWeek(
        schedule: ScheduleSnapshot,
        target: Calendar = Calendar.getInstance(shanghai),
    ): Int? {
        val start = parseContractDate(schedule.termStartDate) ?: return null
        val calculated = weekNumber(start, target)
        val maximum = schedule.courses.flatMap(Course::weekNumbers).filter { it > 0 }.maxOrNull()
            ?: return null
        return calculated.takeIf { it in 1..maximum }
    }

    fun coursesForWeek(schedule: ScheduleSnapshot, week: Int): List<Course> = schedule.courses
        .filter { week in it.weekNumbers }
        .sortedWith(compareBy(Course::weekday, Course::startSlot, Course::name))

    fun maxWeek(schedule: ScheduleSnapshot): Int =
        schedule.courses.flatMap(Course::weekNumbers).filter { it > 0 }.maxOrNull() ?: 1

    fun mondayOfWeek(
        termStartDate: String,
        week: Int,
    ): Calendar? {
        val start = parseContractDate(termStartDate) ?: return null
        return startOfDay(start).apply {
            add(Calendar.DAY_OF_MONTH, (week - 1) * 7)
        }
    }

    fun daysOfWeek(monday: Calendar): List<Calendar> = (0..6).map { offset ->
        (monday.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, offset)
        }
    }

    fun todayIndexInWeek(
        weekDays: List<Calendar>,
        now: Calendar = Calendar.getInstance(shanghai),
    ): Int {
        val todayYear = now.get(Calendar.YEAR)
        val todayDayOfYear = now.get(Calendar.DAY_OF_YEAR)
        return weekDays.indexOfFirst {
            it.get(Calendar.YEAR) == todayYear && it.get(Calendar.DAY_OF_YEAR) == todayDayOfYear
        }
    }

    fun resolveSelectedWeek(
        previousWeek: Int?,
        schedule: ScheduleSnapshot,
        now: Calendar = Calendar.getInstance(shanghai),
    ): Int {
        val max = maxWeek(schedule)
        if (previousWeek != null && previousWeek in 1..max) {
            return previousWeek
        }
        val current = currentWeek(schedule, now)
        if (current != null && current in 1..max) {
            return current
        }
        return 1
    }

    fun resolveRefreshWeek(
        hasExistingSnapshot: Boolean,
        currentSelectedWeek: Int,
        newSnapshot: ScheduleSnapshot,
        now: Calendar = Calendar.getInstance(shanghai),
    ): Int {
        val previousWeek = if (hasExistingSnapshot) currentSelectedWeek else null
        return resolveSelectedWeek(previousWeek, newSnapshot, now)
    }

    fun hasSameBusinessContent(
        current: ScheduleSnapshot?,
        target: ScheduleSnapshot?,
    ): Boolean {
        if (current === target) return true
        if (current == null || target == null) return false
        if (current.termID != target.termID) return false
        if (current.termStartDate != target.termStartDate) return false
        if (current.courses.size != target.courses.size) return false
        if (current.courses == target.courses) return true
        return current.courses.sortedWith(courseComparator) == target.courses.sortedWith(courseComparator)
    }

    fun formatRefreshTime(
        refreshTimeMs: Long,
        now: Calendar = Calendar.getInstance(shanghai),
    ): String {
        val diffMillis = now.timeInMillis - refreshTimeMs
        if (diffMillis < 60_000L) {
            return "\u521a\u521a\u66f4\u65b0"
        }
        if (diffMillis < 3_600_000L) {
            val minutes = (diffMillis / 60_000L).coerceAtLeast(1)
            return "${minutes}\u5206\u949f\u524d"
        }
        val refreshCal = Calendar.getInstance(shanghai).apply {
            timeInMillis = refreshTimeMs
        }
        val isToday = refreshCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            refreshCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (isToday) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = shanghai }
            return "\u4eca\u5929 ${timeFormat.format(refreshCal.time)}"
        }
        val dateFormat = SimpleDateFormat("M\u6708d\u65e5 HH:mm", Locale.CHINA).apply { timeZone = shanghai }
        return dateFormat.format(refreshCal.time)
    }

    fun shouldAutoRefreshToday(
        lastSuccessfulRefreshAt: Long?,
        now: Calendar = Calendar.getInstance(shanghai),
    ): Boolean {
        if (lastSuccessfulRefreshAt == null || lastSuccessfulRefreshAt <= 0L) return true
        val refreshCal = Calendar.getInstance(shanghai).apply {
            timeInMillis = lastSuccessfulRefreshAt
        }
        val isSameDay = refreshCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            refreshCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        return !isSameDay
    }

    fun shouldFetchOnLaunch(
        hasCache: Boolean,
        lastSuccessfulRefreshAt: Long?,
        now: Calendar = Calendar.getInstance(shanghai),
    ): Boolean {
        if (!hasCache) return true
        return shouldAutoRefreshToday(lastSuccessfulRefreshAt, now)
    }

    fun shouldAutoRefreshOnForeground(
        isScheduleVisible: Boolean,
        hasSnapshot: Boolean,
        hasValidCredentials: Boolean,
        isRefreshInFlight: Boolean,
        lastSuccessfulRefreshAt: Long?,
        now: Calendar = Calendar.getInstance(shanghai),
    ): Boolean {
        if (!isScheduleVisible || !hasSnapshot || !hasValidCredentials || isRefreshInFlight) {
            return false
        }
        return shouldAutoRefreshToday(lastSuccessfulRefreshAt, now)
    }

    private val courseComparator = compareBy<Course>(
        { it.id },
        { it.weekday },
        { it.startSlot },
        { it.endSlot },
        { it.name },
        { it.room },
        { it.teacher },
        { it.weekText },
        { it.sourceCourseID ?: "" },
    )

    fun weekNumber(termStart: Calendar, target: Calendar): Int {
        val start = startOfDay(termStart)
        val day = startOfDay(target)
        val elapsedDays = Math.floorDiv(day.timeInMillis - start.timeInMillis, MILLIS_PER_DAY)
        if (elapsedDays < 0) return 0
        return Math.floorDiv(elapsedDays.toInt(), 7) + 1
    }

    fun mondayOfCurrentWeek(target: Calendar = Calendar.getInstance(shanghai)): Calendar =
        startOfDay(target).apply {
            val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) + 5) % 7
            add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        }

    fun parseContractDate(value: String): Calendar? {
        val parts = value.split('-').mapNotNull(String::toIntOrNull)
        if (parts.size != 3) return null
        val date = Calendar.getInstance(shanghai).apply {
            clear()
            isLenient = false
            set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        }
        return runCatching { date.timeInMillis }.map { date }.getOrNull()
    }

    private fun startOfDay(source: Calendar): Calendar = Calendar.getInstance(shanghai).apply {
        clear()
        set(source.get(Calendar.YEAR), source.get(Calendar.MONTH), source.get(Calendar.DAY_OF_MONTH))
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
