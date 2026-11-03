package com.bupt.schedule.domain.logic

import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.AssignmentSyncState
import com.bupt.schedule.domain.model.CustomAssignment
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.ui.model.CourseTone
import java.util.Calendar

object AssignmentPresentation {
    fun merge(cloud: List<Assignment>, local: List<CustomAssignment>, termStart: String?, now: Calendar): List<Assignment> {
        val tasks = local.map { task ->
            val deadline = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI).apply { timeInMillis = task.deadlineMillis }
            Assignment(id = task.id, courseName = task.courseName, title = task.title,
                deadlineText = "", deadlineTimeShort = UCloudAssignmentParser.formatTimeShort(deadline),
                status = AssignmentStatus.PENDING,
                week = 0, weekday = 1, deadlineSlot = 0, description = task.description,
                rawDeadline = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = UCloudAssignmentParser.SHANGHAI
                }.format(deadline.time),
                tone = CourseTone.entries[Math.floorMod(task.courseName.hashCode(), CourseTone.entries.size)],
                isCustom = true)
        }
        val refreshed = (cloud + tasks).map { refresh(it, termStart, now) }
        val deadlines = refreshed.associateWith {
            it.rawDeadline?.let(UCloudAssignmentParser::parseDeadlineDate)?.timeInMillis ?: Long.MAX_VALUE
        }
        return refreshed.sortedWith(
            compareBy<Assignment>({ if (it.status.isFinished) 2 else if (it.status == AssignmentStatus.PENDING) 0 else 1 },
                { deadlines.getValue(it) },
                { it.courseName }, { it.title }, { it.id }))
    }

    fun refresh(item: Assignment, termStart: String?, now: Calendar): Assignment {
        val deadline = item.rawDeadline?.let(UCloudAssignmentParser::parseDeadlineDate) ?: return item
        val overdue = item.taskType.isOverdue(deadline.timeInMillis, now.timeInMillis)
        val status = if (item.status.isFinished) item.status else if (overdue) AssignmentStatus.OVERDUE else AssignmentStatus.PENDING
        return item.copy(status = status,
            deadlineText = UCloudAssignmentParser.formatDeadlineText(deadline, now),
            deadlineTimeShort = UCloudAssignmentParser.formatTimeShort(deadline),
            remainingDaysText = if (status.isFinished) "" else UCloudAssignmentParser.formatRemainingDaysText(deadline, now, overdue),
            week = termStart?.let(ScheduleLogic::parseContractDate)?.let { ScheduleLogic.weekNumber(it, deadline) } ?: 0,
            weekday = ((deadline.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1,
            deadlineSlot = UCloudAssignmentParser.mapTimeToSlot(UCloudAssignmentParser.formatTimeShort(deadline)))
    }

    fun filter(items: List<Assignment>, status: AssignmentStatus?): List<Assignment> =
        if (status == null) items else items.filter { it.status == status }

    fun isFailed(syncState: AssignmentSyncState): Boolean =
        syncState == AssignmentSyncState.FAILED_WITH_CACHE || syncState == AssignmentSyncState.FAILED_NO_CACHE

    fun cloudStatusText(configured: Boolean, syncState: AssignmentSyncState): String = when {
        !configured -> "\u4e91\u90ae\u672a\u914d\u7f6e"
        syncState == AssignmentSyncState.LOADING -> "\u6b63\u5728\u66f4\u65b0\u4efb\u52a1\u2026"
        syncState == AssignmentSyncState.SUCCESS -> "\u66f4\u65b0\u5b8c\u6210"
        syncState == AssignmentSyncState.PARTIAL -> "部分任务未更新"
        isFailed(syncState) -> "\u66f4\u65b0\u5931\u8d25"
        else -> "\u4e91\u90ae\u5df2\u914d\u7f6e"
    }

    fun actionButtonText(configured: Boolean): String =
        if (configured) "\u8bf7\u518d\u6b21\u5c1d\u8bd5" else "\u914d\u7f6e\u4e91\u90ae"

    fun isActionButtonVisible(configured: Boolean, syncState: AssignmentSyncState): Boolean =
        !configured || isFailed(syncState)

    fun emptyStateTitle(configured: Boolean, syncState: AssignmentSyncState): String = when {
        !configured -> "\u4e91\u90ae\u672a\u914d\u7f6e"
        isFailed(syncState) -> "\u66f4\u65b0\u5931\u8d25"
        syncState == AssignmentSyncState.PARTIAL -> "部分任务未更新"
        syncState == AssignmentSyncState.LOADING -> "\u6b63\u5728\u66f4\u65b0\u4efb\u52a1\u2026"
        else -> "\u6682\u65e0\u4efb\u52a1"
    }

    fun emptyStateSubtitle(configured: Boolean, syncState: AssignmentSyncState): String = when {
        !configured -> "\u53f3\u4e0a\u89d2\u53ef\u6dfb\u52a0\u81ea\u5b9a\u4e49\u4f5c\u4e1a\uff0c\u4e5f\u53ef\u914d\u7f6e\u4e91\u90ae\u540c\u6b65\u4efb\u52a1"
        isFailed(syncState) -> "\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u518d\u6b21\u5c1d\u8bd5"
        syncState == AssignmentSyncState.PARTIAL -> "部分来源未完成，可稍后刷新"
        syncState == AssignmentSyncState.LOADING -> "\u8bf7\u7a0d\u5019"
        else -> "\u5f53\u524d\u7b5b\u9009\u4e0b\u6ca1\u6709\u4efb\u52a1\uff0c\u53ef\u5728\u53f3\u4e0a\u89d2\u6dfb\u52a0\u81ea\u5b9a\u4e49\u4f5c\u4e1a"
    }

    fun emptyStateAction(configured: Boolean, syncState: AssignmentSyncState): String? = when {
        !configured -> "\u914d\u7f6e\u4e91\u90ae"
        isFailed(syncState) -> "\u8bf7\u518d\u6b21\u5c1d\u8bd5"
        else -> null
    }

    fun ucloudMessage(error: Throwable): String =
        (error as? ScheduleException)?.message ?: "\u4e91\u90ae\u8fde\u63a5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u91cd\u8bd5\u3002"

    fun syncFailureToast(error: Throwable): String =
        "\u66f4\u65b0\u5931\u8d25\uff1a${ucloudMessage(error)}"
}
