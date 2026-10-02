// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study ScheduleStore.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.data.local

import android.content.Context
import android.util.AtomicFile
import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.domain.model.ScheduleSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object ScheduleJsonCodec {
    fun decode(value: String): ScheduleSnapshot {
        val root = JSONObject(value)
        val courses = root.getJSONArray("courses").objects().map { item ->
            Course(
                id = item.getString("id"),
                name = item.getString("name"),
                teacher = item.optString("teacher"),
                room = item.optString("room"),
                weekText = item.optString("week_text"),
                weekNumbers = item.optJSONArray("week_numbers").integers(),
                weekday = item.getInt("weekday"),
                startSlot = item.getInt("start_slot"),
                endSlot = item.getInt("end_slot"),
                sourceCourseID = item.optString("source_course_id").trim().takeIf(String::isNotEmpty),
                startTime = item.optString("start_time"),
                endTime = item.optString("end_time"),
                teachingClass = item.optString("teaching_class"),
            )
        }
        return ScheduleSnapshot(
            termID = root.getString("term_id"),
            termStartDate = root.getString("term_start_date"),
            fetchedAt = root.getString("fetched_at"),
            courses = courses,
        )
    }

    fun encode(schedule: ScheduleSnapshot): String = JSONObject()
        .put("term_id", schedule.termID)
        .put("term_start_date", schedule.termStartDate)
        .put("fetched_at", schedule.fetchedAt)
        .put("courses", JSONArray().apply {
            schedule.courses.forEach { course ->
                put(
                    JSONObject()
                        .put("id", course.id)
                        .put("name", course.name)
                        .put("teacher", course.teacher)
                        .put("room", course.room)
                        .put("week_text", course.weekText)
                        .put("week_numbers", JSONArray(course.weekNumbers))
                        .put("weekday", course.weekday)
                        .put("start_slot", course.startSlot)
                        .put("end_slot", course.endSlot)
                        .put("source_course_id", course.sourceCourseID)
                        .put("start_time", course.startTime)
                        .put("end_time", course.endTime)
                        .put("teaching_class", course.teachingClass),
                )
            }
        })
        .toString()

    private fun JSONArray?.integers(): List<Int> =
        if (this == null) emptyList() else (0 until length()).map(::getInt)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
}

class ScheduleStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): ScheduleSnapshot? {
        if (!file.baseFile.exists()) return null
        val content = file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return content.takeIf(String::isNotBlank)?.let(ScheduleJsonCodec::decode)
    }

    fun save(schedule: ScheduleSnapshot) {
        val output = file.startWrite()
        try {
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(ScheduleJsonCodec.encode(schedule))
            writer.flush()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun clear() = file.delete()

    private companion object {
        const val FILE_NAME = "schedule.json"
    }
}
