package com.bupt.schedule.domain.repository

import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.Credentials

data class AssignmentRefreshResult(val assignments: List<Assignment>, val complete: Boolean)

interface AssignmentRepository {
    fun loadCached(account: String? = null): List<Assignment>
    fun refresh(credentials: Credentials, termStartDate: String?): List<Assignment> =
        refreshWithCoverage(credentials, termStartDate).assignments
    fun refreshWithCoverage(credentials: Credentials, termStartDate: String?): AssignmentRefreshResult
    fun verifyCredentials(credentials: Credentials)
    fun clearSession()
    fun fetchDetail(assignmentId: String, credentialsProvider: () -> Credentials?): String?
}
