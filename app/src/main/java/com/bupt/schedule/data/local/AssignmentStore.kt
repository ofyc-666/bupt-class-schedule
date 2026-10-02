package com.bupt.schedule.data.local

import android.content.Context
import android.util.AtomicFile
import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.TaskType
import com.bupt.schedule.ui.model.CourseTone
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Calendar

object AssignmentJsonCodec {
    fun accountOwnerHash(account: String): String {
        val bytes = account.trim().toByteArray(StandardCharsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encode(account: String, assignments: List<Assignment>): String {
        val root = JSONObject()
        root.put("owner_hash", accountOwnerHash(account))
        val array = JSONArray()
        assignments.forEach { item ->
            val obj = JSONObject()
                .put("id", item.id)
                .put("course_name", item.courseName)
                .put("course_id", item.courseId)
                .put("title", item.title)
                .put("deadline_text", item.deadlineText)
                .put("deadline_time_short", item.deadlineTimeShort)
                .put("status", item.status.name)
                .put("week", item.week)
                .put("weekday", item.weekday)
                .put("deadline_slot", item.deadlineSlot)
                .put("description", item.description)
                .put("score", item.score)
                .put("remaining_days_text", item.remainingDaysText)
                .put("tone", item.tone.name)
                .put("chapter_name", item.chapterName)
                .put("raw_deadline", item.rawDeadline)
                .put("task_type", item.taskType.name)
            array.put(obj)
        }
        root.put("assignments", array)
        return root.toString()
    }

    fun encode(assignments: List<Assignment>): String = encode("default_owner", assignments)

    fun decode(
        value: String,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> = decode(value, "default_owner", nowCalendar)

    fun decode(
        value: String,
        expectedAccount: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || expectedAccount.isNullOrBlank()) return emptyList()

        val expectedHash = accountOwnerHash(expectedAccount)
        val array = if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val ownerHash = root.optString("owner_hash")
            if (ownerHash.isEmpty() || ownerHash != expectedHash) {
                return emptyList()
            }
            root.optJSONArray("assignments") ?: JSONArray()
        } else if (trimmed.startsWith("[")) {
            // 旧版无 owner 的 assignments cache 保守视为不可归属，不参与跨账号合并与复用
            return emptyList()
        } else {
            return emptyList()
        }

        val result = ArrayList<Assignment>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val rawDeadline = obj.optString("raw_deadline").takeIf { it.isNotEmpty() }
            val taskType = runCatching { TaskType.valueOf(obj.optString("task_type", "ASSIGNMENT")) }
                .getOrDefault(TaskType.ASSIGNMENT)
            var status = runCatching { AssignmentStatus.valueOf(obj.optString("status")) }
                .getOrDefault(AssignmentStatus.PENDING)
            var deadlineText = obj.getString("deadline_text")
            var deadlineTimeShort = obj.getString("deadline_time_short")
            var remainingDaysText = obj.optString("remaining_days_text")

            if (!rawDeadline.isNullOrBlank()) {
                val deadlineCal = UCloudAssignmentParser.parseDeadlineDate(rawDeadline)
                if (deadlineCal != null) {
                    val isOverdue = if (taskType == TaskType.QUIZ) deadlineCal.timeInMillis < nowCalendar.timeInMillis
                        else deadlineCal.timeInMillis <= nowCalendar.timeInMillis
                    status = if (isOverdue) AssignmentStatus.OVERDUE else AssignmentStatus.PENDING
                    deadlineText = UCloudAssignmentParser.formatDeadlineText(deadlineCal, nowCalendar)
                    deadlineTimeShort = UCloudAssignmentParser.formatTimeShort(deadlineCal)
                    remainingDaysText = UCloudAssignmentParser.formatRemainingDaysText(deadlineCal, nowCalendar, isOverdue)
                }
            }

            val toneName = obj.optString("tone")
            val tone = runCatching { CourseTone.valueOf(toneName) }.getOrDefault(CourseTone.SKY)

            result.add(
                Assignment(
                    id = obj.getString("id"),
                    courseName = obj.getString("course_name"),
                    courseId = obj.optString("course_id").takeIf { it.isNotEmpty() },
                    title = obj.getString("title"),
                    deadlineText = deadlineText,
                    deadlineTimeShort = deadlineTimeShort,
                    status = status,
                    week = obj.getInt("week"),
                    weekday = obj.getInt("weekday"),
                    deadlineSlot = obj.getInt("deadline_slot"),
                    description = obj.optString("description"),
                    score = obj.optString("score").takeIf { it.isNotEmpty() },
                    remainingDaysText = remainingDaysText,
                    tone = tone,
                    chapterName = obj.optString("chapter_name").takeIf { it.isNotEmpty() },
                    rawDeadline = rawDeadline,
                    taskType = taskType,
                )
            )
        }
        return result
    }
}

open class AssignmentStore {
    private val file: AtomicFile?

    constructor(context: Context) {
        file = AtomicFile(File(context.filesDir, FILE_NAME))
    }

    constructor() {
        file = null
    }

    open fun load(nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI)): List<Assignment> =
        load("default_owner", nowCalendar)

    open fun save(assignments: List<Assignment>) = save("default_owner", assignments)

    open fun load(
        account: String?,
        nowCalendar: Calendar = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI),
    ): List<Assignment> {
        if (account.isNullOrBlank()) return emptyList()
        val f = file ?: return emptyList()
        if (!f.baseFile.exists()) return emptyList()
        return runCatching {
            val content = f.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (content.isBlank()) emptyList() else AssignmentJsonCodec.decode(content, account, nowCalendar)
        }.getOrElse { emptyList() }
    }

    open fun save(account: String, assignments: List<Assignment>) {
        val f = file ?: return
        val output = f.startWrite()
        try {
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(AssignmentJsonCodec.encode(account, assignments))
            writer.flush()
            f.finishWrite(output)
        } catch (error: Exception) {
            f.failWrite(output)
            throw error
        }
    }

    open fun clear() {
        file?.let { runCatching { it.delete() } }
    }

    open fun clear(account: String) {
        val cleanAccount = account.trim()
        if (cleanAccount.isNotEmpty()) {
            runCatching { save(cleanAccount, emptyList()) }
        }
        clear()
    }

    private companion object {
        const val FILE_NAME = "assignments.json"
    }
}
