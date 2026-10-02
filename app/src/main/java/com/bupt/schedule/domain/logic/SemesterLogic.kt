// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study SemesterLogic.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.domain.logic

import com.bupt.schedule.domain.model.ScheduleSnapshot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class SuggestedTerm(val termID: String, val termStartDate: String)

object SemesterLogic {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val springMonths = 2..7

    fun suggest(date: Calendar = Calendar.getInstance(shanghai)): SuggestedTerm {
        val month = date.get(Calendar.MONTH) + 1
        val year = date.get(Calendar.YEAR)
        return if (month in springMonths) {
            SuggestedTerm("${year - 1}-$year-2", mondayContaining(year, 3, 2))
        } else {
            val fallYear = if (month == 1) year - 1 else year
            SuggestedTerm("$fallYear-${fallYear + 1}-1", mondayContaining(fallYear, 9, 1))
        }
    }

    fun canUseCached(snapshot: ScheduleSnapshot, date: Calendar = Calendar.getInstance(shanghai)): Boolean =
        snapshot.termID == suggest(date).termID && isValidDate(snapshot.termStartDate)

    fun isValidDate(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = shanghai
            isLenient = false
        }
        val parsed = runCatching { formatter.parse(value) }.getOrNull() ?: return false
        return formatter.format(parsed) == value
    }

    private fun mondayContaining(year: Int, month: Int, day: Int): String {
        val date = Calendar.getInstance(shanghai).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }
        date.add(Calendar.DAY_OF_MONTH, -((date.get(Calendar.DAY_OF_WEEK) + 5) % 7))
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH) + 1,
            date.get(Calendar.DAY_OF_MONTH),
        )
    }
}
