package com.bupt.schedule.data

import com.bupt.schedule.data.local.AssignmentStore
import com.bupt.schedule.data.remote.UCloudAssignmentClient
import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.data.remote.UCloudSession
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.TaskType
import com.bupt.schedule.domain.repository.AssignmentRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class DefaultAssignmentRepository(
    private val client: UCloudAssignmentClient = UCloudAssignmentClient(),
    private val store: AssignmentStore,
) : AssignmentRepository {

    private val cachedSession = AtomicReference<UCloudSession?>()
    private val detailCache = ConcurrentHashMap<String, String>()

    override fun loadCached(account: String?): List<Assignment> {
        if (account.isNullOrBlank()) return emptyList()
        return store.load(account)
    }

    override fun refresh(credentials: Credentials, termStartDate: String?): List<Assignment> {
        val account = credentials.account.trim()
        if (credentials.teachingCloudPassword.isNullOrEmpty()) {
            return store.load(account)
        }

        val session = client.login(credentials)
        cachedSession.set(session)
        val syncResult = client.fetchAll(session, termStartDate)

        val mergedAssignments = if (syncResult.allCoursesSucceeded) {
            syncResult.assignments
        } else {
            val oldCached = store.load(credentials.account)
            val keptFromOld = oldCached.filter { oldItem ->
                val siteId = oldItem.courseId
                val successfulSourceSites = if (oldItem.taskType == TaskType.QUIZ)
                    syncResult.successfulQuizSiteIds else syncResult.successfulAssignmentSiteIds
                siteId == null || siteId !in successfulSourceSites
            }
            UCloudAssignmentParser.mergeAndSort(syncResult.assignments + keptFromOld)
        }

        store.save(credentials.account, mergedAssignments)
        return mergedAssignments
    }

    override fun verifyCredentials(credentials: Credentials) {
        require(!credentials.teachingCloudPassword.isNullOrBlank())
        client.login(credentials)
    }

    override fun clearSession() {
        cachedSession.set(null)
        detailCache.clear()
    }

    override fun fetchDetail(assignmentId: String, credentialsProvider: () -> Credentials?): String? {
        val cached = detailCache[assignmentId]
        if (cached != null) return cached

        val session = cachedSession.get() ?: run {
            val creds = credentialsProvider() ?: return null
            if (creds.teachingCloudPassword.isNullOrEmpty()) return null
            val newSession = client.login(creds)
            cachedSession.set(newSession)
            newSession
        }

        return runCatching {
            val content = client.fetchDetail(session, assignmentId)
            detailCache[assignmentId] = content
            content
        }.recoverCatching {
            val creds = credentialsProvider() ?: return@recoverCatching null
            if (creds.teachingCloudPassword.isNullOrEmpty()) return@recoverCatching null
            val freshSession = client.login(creds)
            cachedSession.set(freshSession)
            val content = client.fetchDetail(freshSession, assignmentId)
            detailCache[assignmentId] = content
            content
        }.getOrNull()
    }
}
