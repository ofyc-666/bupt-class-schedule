package com.bupt.schedule.data

import com.bupt.schedule.data.local.AssignmentStore
import com.bupt.schedule.data.remote.UCloudAssignmentClient
import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.data.remote.UCloudSession
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.repository.AssignmentRefreshResult
import com.bupt.schedule.domain.repository.AssignmentRepository

class DefaultAssignmentRepository(
    private val client: UCloudAssignmentClient = UCloudAssignmentClient(),
    private val store: AssignmentStore,
) : AssignmentRepository {

    private val stateLock = Any()
    private var generation = 0L
    private var refreshingThread: Thread? = null
    private var sessionAccount: String? = null
    private var cachedSession: UCloudSession? = null
    private val detailCache = mutableMapOf<String, String>()

    override fun loadCached(account: String?): List<Assignment> {
        if (account.isNullOrBlank()) return emptyList()
        return store.load(account)
    }

    override fun refreshWithCoverage(credentials: Credentials, termStartDate: String?): AssignmentRefreshResult {
        val account = credentials.account.trim()
        if (credentials.teachingCloudPassword.isNullOrEmpty()) {
            return AssignmentRefreshResult(store.load(account), complete = true)
        }

        val refreshGeneration = synchronized(stateLock) {
            check(refreshingThread == null) { "教学云任务正在刷新。" }
            selectAccount(account)
            refreshingThread = Thread.currentThread()
            ++generation
        }
        try {
            val session = client.login(credentials)
            synchronized(stateLock) {
                checkActive(refreshGeneration)
                cachedSession = session
            }
            val syncResult = client.fetchAll(session, termStartDate)

            return synchronized(stateLock) {
                checkActive(refreshGeneration)
                val merged = if (syncResult.allCoursesSucceeded) {
                    syncResult.assignments
                } else {
                    val keptFromOld = store.load(account).filter { old ->
                        old.courseId !in syncResult.successfulSites(old.taskType)
                    }
                    UCloudAssignmentParser.mergeAndSort(syncResult.assignments + keptFromOld)
                }
                store.save(account, merged)
                AssignmentRefreshResult(merged, syncResult.allCoursesSucceeded)
            }
        } finally {
            synchronized(stateLock) { refreshingThread = null }
        }
    }

    override fun verifyCredentials(credentials: Credentials) {
        require(!credentials.teachingCloudPassword.isNullOrBlank())
        client.login(credentials)
    }

    override fun clearSession() {
        synchronized(stateLock) {
            selectAccount(null)
            refreshingThread?.interrupt()
        }
    }

    private fun selectAccount(account: String?) {
        if (account == null || account != sessionAccount) {
            generation++
            sessionAccount = account
            cachedSession = null
            detailCache.clear()
        }
    }

    private fun checkActive(expectedGeneration: Long) {
        if (generation != expectedGeneration || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    override fun fetchDetail(assignmentId: String, credentialsProvider: () -> Credentials?): String? {
        val credentials = credentialsProvider() ?: return null
        if (credentials.teachingCloudPassword.isNullOrEmpty()) return null
        val (detailGeneration, session) = synchronized(stateLock) {
            selectAccount(credentials.account.trim())
            detailCache[assignmentId]?.let { return it }
            generation to cachedSession
        }
        fun login(): UCloudSession {
            synchronized(stateLock) { checkActive(detailGeneration) }
            val fresh = client.login(credentials)
            synchronized(stateLock) {
                checkActive(detailGeneration)
                cachedSession = fresh
            }
            return fresh
        }
        return try {
            val content = try {
                client.fetchDetail(session ?: login(), assignmentId)
            } catch (error: ScheduleException) {
                if (session == null || error.statusCode != 401) throw error
                client.fetchDetail(login(), assignmentId)
            }
            synchronized(stateLock) {
                checkActive(detailGeneration)
                detailCache[assignmentId] = content
            }
            content
        } catch (_: Exception) {
            null
        }
    }
}
