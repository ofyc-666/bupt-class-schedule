package com.bupt.schedule.domain.logic

import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class AssignmentPresentationTest {
    private fun date(value: String) = UCloudAssignmentParser.parseDeadlineDate(value)!!
    private fun task(deadline: String = "2026-10-11 23:59:00") =
        CustomAssignment(id = "LOCAL:demo", title = "local", courseName = "手输课程", deadlineMillis = date(deadline).timeInMillis)
    private fun merge(local: List<CustomAssignment>, now: String, term: String? = "2026-09-07", cloud: List<Assignment> = emptyList()) =
        AssignmentPresentation.merge(cloud, local, term, date(now))
    private fun cloud(deadline: String, status: AssignmentStatus = AssignmentStatus.PENDING, type: TaskType = TaskType.ASSIGNMENT) =
        Assignment(id = "demo", courseName = "cloud", title = "cloud", deadlineText = "", deadlineTimeShort = "",
            status = status, week = 99, weekday = 1, deadlineSlot = 0, rawDeadline = deadline, taskType = type)

    @Test fun deadlineUpdatesStatusAndFiltersKeepCloudFinishedStates() {
        val before = merge(listOf(task()), "2026-10-11 23:58:59").single()
        val at = merge(listOf(task()), "2026-10-11 23:59:00").single()
        val submitted = cloud("2026-10-11 23:59:00", AssignmentStatus.SUBMITTED)
        assertEquals(AssignmentStatus.PENDING, before.status)
        assertEquals(AssignmentStatus.OVERDUE, at.status)
        val items = listOf(before, at, submitted)
        assertEquals(items, AssignmentPresentation.filter(items, null))
        assertEquals(listOf(before), AssignmentPresentation.filter(items, AssignmentStatus.PENDING))
        assertEquals(listOf(at), AssignmentPresentation.filter(items, AssignmentStatus.OVERDUE))
        assertTrue(submitted.status.isFinished)
    }

    @Test fun localWorksWithNoCloudOrSemesterAndIsUnaffectedByCloudReplacement() {
        val local = task()
        val now = "2026-10-01 00:00:00"
        val withoutCloud = merge(listOf(local), now, term = null).single()
        assertEquals(0, withoutCloud.week)
        assertTrue(withoutCloud.isCustom)
        val merged = merge(listOf(local), now, cloud = listOf(cloud("2026-10-09 12:00:00")))
        assertEquals(2, merged.size)
        assertEquals("demo", merged.first().id)
        assertEquals(local.id, merged.last().id)
        assertEquals(withoutCloud.copy(week = 5), merged.last())
        assertEquals(local, task()) // Presentation never mutates the stored task.
    }

    @Test fun correctTimelineAtSundayMidnightMonthYearAndSemesterBoundaries() {
        val sunday = merge(listOf(task()), "2026-10-10 00:00:00").single()
        assertEquals(5, sunday.week)
        assertEquals(7, sunday.weekday)
        assertEquals(13, sunday.deadlineSlot)
        assertEquals("23:59", sunday.deadlineTimeShort)
        val monday = merge(listOf(task("2026-10-12 00:00:00")), "2026-10-10 00:00:00").single()
        assertEquals(6, monday.week)
        assertEquals(1, monday.weekday)
        assertEquals(0, monday.deadlineSlot)
        val january = merge(listOf(task("2027-01-01 23:59:00")), "2026-12-31 00:00:00").single()
        assertEquals(17, january.week)
        assertEquals(5, january.weekday)
        val beforeSemester = merge(listOf(task("2026-09-06 23:59:00")), "2026-09-01 00:00:00").single()
        assertEquals(0, beforeSemester.week)
        assertEquals(1, merge(listOf(task("2026-09-07 00:00:00")), "2026-09-01 00:00:00").single().week)
        assertEquals(0, merge(listOf(task()), "2026-10-01 00:00:00", "2027-03-01").single().week)
    }

    @Test fun cloudStatusesAndQuizExactDeadlineRuleArePreservedAndWeekIsRebased() {
        val deadline = "2026-10-11 23:59:00"
        val submitted = merge(emptyList(), "2026-10-12 00:00:00", cloud = listOf(cloud(deadline, AssignmentStatus.SUBMITTED))).single()
        assertEquals(AssignmentStatus.SUBMITTED, submitted.status)
        assertEquals(5, submitted.week)
        assertFalse(submitted.isCustom)
        assertEquals(AssignmentStatus.PENDING, merge(emptyList(), deadline, cloud = listOf(cloud(deadline, type = TaskType.QUIZ))).single().status)
        assertEquals(AssignmentStatus.OVERDUE, merge(emptyList(), deadline, cloud = listOf(cloud(deadline))).single().status)
    }

    @Test fun editingMovesTimeline() {
        val local = task()
        val edited = local.copy(deadlineMillis = date("2026-10-12 14:45:00").timeInMillis)
        val item = merge(listOf(edited), "2026-10-11 00:00:00").single()
        assertEquals(6, item.week)
        assertEquals(1, item.weekday)
        assertEquals(7, item.deadlineSlot)
        assertEquals(1, merge(listOf(edited), "2026-10-11 00:00:00").filter { it.week == 6 && !it.status.isFinished }.size)
    }

    @Test
    fun testSyncStatePresentation_unconfigured() {
        assertEquals("云邮未配置", AssignmentPresentation.cloudStatusText(false, AssignmentSyncState.IDLE))
        assertEquals("配置云邮", AssignmentPresentation.actionButtonText(false))
        assertTrue(AssignmentPresentation.isActionButtonVisible(false, AssignmentSyncState.IDLE))
        assertEquals("云邮未配置", AssignmentPresentation.emptyStateTitle(false, AssignmentSyncState.IDLE))
        assertEquals("右上角可添加自定义作业，也可配置云邮同步任务", AssignmentPresentation.emptyStateSubtitle(false, AssignmentSyncState.IDLE))
        assertEquals("配置云邮", AssignmentPresentation.emptyStateAction(false, AssignmentSyncState.IDLE))
    }

    @Test
    fun testSyncStatePresentation_failedWithCache() {
        assertTrue(AssignmentPresentation.isFailed(AssignmentSyncState.FAILED_WITH_CACHE))
        assertEquals("更新失败", AssignmentPresentation.cloudStatusText(true, AssignmentSyncState.FAILED_WITH_CACHE))
        assertEquals("请再次尝试", AssignmentPresentation.actionButtonText(true))
        assertTrue(AssignmentPresentation.isActionButtonVisible(true, AssignmentSyncState.FAILED_WITH_CACHE))
        assertFalse(AssignmentPresentation.cloudStatusText(true, AssignmentSyncState.FAILED_WITH_CACHE).contains("密码"))
        assertFalse(AssignmentPresentation.actionButtonText(true).contains("密码"))
        assertFalse(AssignmentPresentation.actionButtonText(true).contains("验证"))
    }

    @Test
    fun testSyncStatePresentation_failedNoCache() {
        assertTrue(AssignmentPresentation.isFailed(AssignmentSyncState.FAILED_NO_CACHE))
        assertEquals("更新失败", AssignmentPresentation.cloudStatusText(true, AssignmentSyncState.FAILED_NO_CACHE))
        assertEquals("请再次尝试", AssignmentPresentation.actionButtonText(true))
        assertTrue(AssignmentPresentation.isActionButtonVisible(true, AssignmentSyncState.FAILED_NO_CACHE))
        assertEquals("更新失败", AssignmentPresentation.emptyStateTitle(true, AssignmentSyncState.FAILED_NO_CACHE))
        assertEquals("请检查网络后再次尝试", AssignmentPresentation.emptyStateSubtitle(true, AssignmentSyncState.FAILED_NO_CACHE))
        assertEquals("请再次尝试", AssignmentPresentation.emptyStateAction(true, AssignmentSyncState.FAILED_NO_CACHE))
        assertFalse(AssignmentPresentation.emptyStateSubtitle(true, AssignmentSyncState.FAILED_NO_CACHE).contains("密码"))
        assertFalse(AssignmentPresentation.emptyStateAction(true, AssignmentSyncState.FAILED_NO_CACHE)?.contains("密码") ?: false)
    }

    @Test
    fun testSyncStatePresentation_success() {
        assertFalse(AssignmentPresentation.isFailed(AssignmentSyncState.SUCCESS))
        assertEquals("更新完成", AssignmentPresentation.cloudStatusText(true, AssignmentSyncState.SUCCESS))
        assertFalse(AssignmentPresentation.isActionButtonVisible(true, AssignmentSyncState.SUCCESS))
        assertEquals("暂无任务", AssignmentPresentation.emptyStateTitle(true, AssignmentSyncState.SUCCESS))
        assertEquals("当前筛选下没有任务，可在右上角添加自定义作业", AssignmentPresentation.emptyStateSubtitle(true, AssignmentSyncState.SUCCESS))
        assertNull(AssignmentPresentation.emptyStateAction(true, AssignmentSyncState.SUCCESS))
    }

    @Test
    fun testSyncStatePresentation_loading() {
        assertFalse(AssignmentPresentation.isFailed(AssignmentSyncState.LOADING))
        assertEquals("正在更新任务…", AssignmentPresentation.cloudStatusText(true, AssignmentSyncState.LOADING))
        assertFalse(AssignmentPresentation.isActionButtonVisible(true, AssignmentSyncState.LOADING))
        assertEquals("正在更新任务…", AssignmentPresentation.emptyStateTitle(true, AssignmentSyncState.LOADING))
        assertEquals("请稍候", AssignmentPresentation.emptyStateSubtitle(true, AssignmentSyncState.LOADING))
        assertNull(AssignmentPresentation.emptyStateAction(true, AssignmentSyncState.LOADING))
    }

    @Test
    fun testErrorMessagesAndToast_neverRequirePasswordCheck() {
        val networkError = ScheduleException(
            ScheduleFailureKind.NETWORK_UNREACHABLE,
            "无法连接教学云，请检查当前网络后重试。"
        )
        val networkMsg = AssignmentPresentation.ucloudMessage(networkError)
        val networkToast = AssignmentPresentation.syncFailureToast(networkError)
        assertEquals("无法连接教学云，请检查当前网络后重试。", networkMsg)
        assertEquals("更新失败：无法连接教学云，请检查当前网络后重试。", networkToast)
        assertFalse(networkMsg.contains("密码"))
        assertFalse(networkToast.contains("密码"))

        val genericError = RuntimeException("Connection reset by peer")
        val genericMsg = AssignmentPresentation.ucloudMessage(genericError)
        val genericToast = AssignmentPresentation.syncFailureToast(genericError)
        assertEquals("云邮连接失败，请检查网络后重试。", genericMsg)
        assertEquals("更新失败：云邮连接失败，请检查网络后重试。", genericToast)
        assertFalse(genericMsg.contains("密码"))
        assertFalse(genericToast.contains("密码"))
    }

    @Test
    fun testAllAssignmentTypesHaveUnifiedDeadlineFormatting() {
        val now = "2026-10-15 10:00:00"
        val deadlineSameYear = "2026-10-16 23:59:00"
        val deadlineNextYear = "2027-01-02 23:59:00"

        // 1. Same year
        val localSameYear = CustomAssignment(
            id = "LOCAL:same",
            title = "Custom",
            courseName = "Course 1",
            deadlineMillis = date(deadlineSameYear).timeInMillis
        )
        val cloudSameYear = cloud(deadlineSameYear)
        val quizSameYear = cloud(deadlineSameYear, type = TaskType.QUIZ)

        val mergedSameYear = AssignmentPresentation.merge(
            listOf(cloudSameYear, quizSameYear),
            listOf(localSameYear),
            termStart = "2026-09-01",
            now = date(now)
        )

        for (item in mergedSameYear) {
            assertEquals("10月16日 23:59", item.deadlineText)
            assertEquals("10月16日 23:59 截止", "${item.deadlineText} 截止")
            assertEquals("23:59", item.deadlineTimeShort)
        }

        // 2. Next year
        val localNextYear = CustomAssignment(
            id = "LOCAL:next",
            title = "Custom Next",
            courseName = "Course 2",
            deadlineMillis = date(deadlineNextYear).timeInMillis
        )
        val cloudNextYear = cloud(deadlineNextYear)
        val quizNextYear = cloud(deadlineNextYear, type = TaskType.QUIZ)

        val mergedNextYear = AssignmentPresentation.merge(
            listOf(cloudNextYear, quizNextYear),
            listOf(localNextYear),
            termStart = "2026-09-01",
            now = date(now)
        )

        for (item in mergedNextYear) {
            assertEquals("2027年1月2日 23:59", item.deadlineText)
            assertEquals("2027年1月2日 23:59 截止", "${item.deadlineText} 截止")
            assertEquals("23:59", item.deadlineTimeShort)
        }
    }

    @Test
    fun testRemainingTimeAndStatusPreservedWithNewDeadlineFormat() {
        val now = date("2026-10-15 12:00:00")
        val futureDeadline = "2026-10-17 12:00:00"
        val pastDeadline = "2026-10-14 12:00:00"

        val future = cloud(futureDeadline)
        val past = cloud(pastDeadline)

        val refreshedFuture = AssignmentPresentation.refresh(future, "2026-09-01", now)
        val refreshedPast = AssignmentPresentation.refresh(past, "2026-09-01", now)

        assertEquals("10月17日 12:00", refreshedFuture.deadlineText)
        assertEquals(AssignmentStatus.PENDING, refreshedFuture.status)
        assertEquals("剩余 2 天", refreshedFuture.remainingDaysText)

        assertEquals("10月14日 12:00", refreshedPast.deadlineText)
        assertEquals(AssignmentStatus.OVERDUE, refreshedPast.status)
        assertEquals("已截止", refreshedPast.remainingDaysText)
    }
}
