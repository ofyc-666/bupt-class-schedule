package com.bupt.schedule.domain.logic

import com.bupt.schedule.data.local.CustomAssignmentStore
import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.CustomAssignment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomAssignmentCompletionTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = CustomAssignmentStore(object : CustomAssignmentStore.Storage {
        override fun read(ownerHash: String) = File(folder.root, ownerHash).takeIf { it.exists() }?.readText()
        override fun write(ownerHash: String, value: String) { File(folder.root, ownerHash).writeText(value) }
    })

    @Test fun completingPendingOrOverdueTasksRemovesAllListsAndTimelinePermanently() {
        val deadline = UCloudAssignmentParser.parseDeadlineDate("2026-10-11 23:59:00")!!
        val cloud = Assignment(id = "cloud-demo", courseName = "云邮课程", title = "真实云邮任务",
            deadlineText = "", deadlineTimeShort = "23:59", status = AssignmentStatus.PENDING,
            week = 5, weekday = 7, deadlineSlot = 13, rawDeadline = "2026-10-11 23:59:00")
        for (nowText in listOf("2026-10-11 12:00:00", "2026-10-12 12:00:00")) {
            val now = UCloudAssignmentParser.parseDeadlineDate(nowText)!!
            val task = CustomAssignment(title = "练习", courseName = "本地课程", deadlineMillis = deadline.timeInMillis)
            store().upsert("demo-account", task)
            store().upsert("other-account", task)
            val otherAccountTasks = store().load("other-account")
            fun presentation(cloudTasks: List<Assignment>) = AssignmentPresentation.merge(
                cloudTasks, store().load("demo-account"), "2026-09-07", now)
            assertTrue(presentation(listOf(cloud)).any { it.id == task.id })
            store().complete("demo-account", task.id)
            // Each merge recreates the store from actual files, like re-opening the app.
            for (cloudTasks in listOf(emptyList(), listOf(cloud))) {
                val remaining = presentation(cloudTasks)
                for (filter in listOf(null, AssignmentStatus.PENDING, AssignmentStatus.OVERDUE)) {
                    assertTrue(AssignmentPresentation.filter(remaining, filter).none { it.id == task.id })
                }
                assertTrue(remaining.filter { it.week == 5 && !it.status.isFinished }.none { it.id == task.id })
                assertEquals(cloudTasks.map { it.id }, remaining.map { it.id })
            }
            assertEquals(otherAccountTasks, store().load("other-account"))
            // A stale repeat click cannot restore the removed task.
            store().complete("demo-account", task.id)
            assertTrue(store().load("demo-account").isEmpty())
            assertFalse(File(folder.root, com.bupt.schedule.data.local.AssignmentJsonCodec.accountOwnerHash("demo-account"))
                .readText().contains(task.id))
        }
    }
}
