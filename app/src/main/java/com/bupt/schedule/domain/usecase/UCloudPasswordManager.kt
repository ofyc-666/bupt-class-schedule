package com.bupt.schedule.domain.usecase

import com.bupt.schedule.domain.model.Credentials

/** Keeps password verification ahead of encrypted credential persistence. */
class UCloudPasswordManager(
    private val verify: (Credentials) -> Unit,
    private val save: (Credentials) -> Unit,
    private val isCurrent: (Credentials) -> Boolean = { true },
) {
    fun configure(credentials: Credentials, password: String): Credentials {
        require(password.isNotBlank())
        val updated = credentials.copy(teachingCloudPassword = password)
        verify(updated)
        check(isCurrent(credentials)) { "账号已切换，请重新配置云邮密码" }
        save(updated)
        return updated
    }

    fun clear(credentials: Credentials): Credentials {
        val updated = credentials.copy(teachingCloudPassword = null)
        save(updated)
        return updated
    }
}
