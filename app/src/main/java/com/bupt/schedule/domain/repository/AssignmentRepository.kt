package com.bupt.schedule.domain.repository

import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.Credentials

interface AssignmentRepository {
    fun loadCached(account: String? = null): List<Assignment>
    fun refresh(credentials: Credentials, termStartDate: String?): List<Assignment>
    fun verifyCredentials(credentials: Credentials)
    fun clearSession()
    fun fetchDetail(assignmentId: String, credentialsProvider: () -> Credentials?): String?
}
