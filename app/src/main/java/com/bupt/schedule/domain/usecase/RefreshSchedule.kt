package com.bupt.schedule.domain.usecase

import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleSnapshot
import com.bupt.schedule.domain.repository.ScheduleRepository

enum class CredentialSaveMode {
    KEEP_EXISTING,
    SAVE_AFTER_REFRESH_SUCCESS,
}

/**
 * Keeps the ordering between remote refresh and credential persistence explicit.
 * A refresh exception prevents the credential write from being reached.
 */
class RefreshSchedule(
    private val repository: ScheduleRepository,
    private val saveCredentials: (Credentials) -> Unit,
    private val onRefreshSuccess: ((Long) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun execute(
        credentials: Credentials,
        credentialSaveMode: CredentialSaveMode,
    ): ScheduleSnapshot {
        val snapshot = repository.refresh(credentials)
        if (credentialSaveMode == CredentialSaveMode.SAVE_AFTER_REFRESH_SUCCESS) {
            saveCredentials(credentials)
        }
        onRefreshSuccess?.invoke(clock())
        return snapshot
    }
}
