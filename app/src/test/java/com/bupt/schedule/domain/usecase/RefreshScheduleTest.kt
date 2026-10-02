package com.bupt.schedule.domain.usecase

import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleSnapshot
import com.bupt.schedule.domain.repository.ScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RefreshScheduleTest {
    @Test
    fun `schedule login succeeds without optional UCloud password`() {
        var saved: Credentials? = null
        val credentials = Credentials("student", "teaching-password", null)
        val useCase = RefreshSchedule(
            repository = SuccessfulRepository,
            saveCredentials = { saved = it },
        )

        assertEquals(SNAPSHOT, useCase.execute(credentials, CredentialSaveMode.SAVE_AFTER_REFRESH_SUCCESS))
        assertEquals(credentials, saved)
        assertNull(saved?.teachingCloudPassword)
    }

    @Test
    fun `first login saves teaching credentials before optional UCloud verification`() {
        var saved: Credentials? = null
        val entered = Credentials("student", "teaching-password", "wrong-ucloud-password")
        val useCase = RefreshSchedule(
            repository = SuccessfulRepository,
            saveCredentials = { saved = it },
        )

        useCase.execute(entered.forTeachingLogin(), CredentialSaveMode.SAVE_AFTER_REFRESH_SUCCESS)
        assertEquals("student", saved?.account)
        assertEquals("teaching-password", saved?.password)
        assertNull(saved?.teachingCloudPassword)

        val manager = UCloudPasswordManager(
            verify = { throw IllegalArgumentException("invalid UCloud password") },
            save = { saved = it },
        )
        assertThrows(IllegalArgumentException::class.java) {
            manager.configure(saved!!, entered.teachingCloudPassword!!)
        }
        assertNull(saved?.teachingCloudPassword)
    }

    @Test
    fun `failed refresh does not save new credentials`() {
        val existing = Credentials("existing-account", "existing-password")
        var savedCredentials: Credentials? = existing
        val useCase = RefreshSchedule(
            repository = FailingRepository,
            saveCredentials = { savedCredentials = it },
        )

        assertThrows(IllegalStateException::class.java) {
            useCase.execute(
                Credentials("new-account", "wrong-password"),
                CredentialSaveMode.SAVE_AFTER_REFRESH_SUCCESS,
            )
        }

        assertEquals(existing, savedCredentials)
    }

    @Test
    fun `successful refresh saves credentials afterwards`() {
        var savedCredentials: Credentials? = null
        val credentials = Credentials("new-account", "correct-password")
        val useCase = RefreshSchedule(
            repository = SuccessfulRepository,
            saveCredentials = { savedCredentials = it },
        )

        val result = useCase.execute(
            credentials,
            CredentialSaveMode.SAVE_AFTER_REFRESH_SUCCESS,
        )

        assertEquals(SNAPSHOT, result)
        assertEquals(credentials, savedCredentials)
    }

    @Test
    fun `background refresh keeps existing credentials without rewriting`() {
        var savedCredentials: Credentials? = null
        val useCase = RefreshSchedule(
            repository = SuccessfulRepository,
            saveCredentials = { savedCredentials = it },
        )

        useCase.execute(
            Credentials("existing-account", "existing-password"),
            CredentialSaveMode.KEEP_EXISTING,
        )

        assertNull(savedCredentials)
    }

    @Test
    fun `successful refresh updates last refresh time`() {
        var recordedTime: Long? = null
        val useCase = RefreshSchedule(
            repository = SuccessfulRepository,
            saveCredentials = {},
            onRefreshSuccess = { recordedTime = it },
            clock = { 1726230000000L },
        )

        useCase.execute(
            Credentials("account", "password"),
            CredentialSaveMode.KEEP_EXISTING,
        )

        assertEquals(1726230000000L, recordedTime)
    }

    @Test
    fun `failed refresh does not update last refresh time`() {
        var recordedTime: Long? = null
        val useCase = RefreshSchedule(
            repository = FailingRepository,
            saveCredentials = {},
            onRefreshSuccess = { recordedTime = it },
            clock = { 1726230000000L },
        )

        assertThrows(IllegalStateException::class.java) {
            useCase.execute(
                Credentials("account", "password"),
                CredentialSaveMode.KEEP_EXISTING,
            )
        }

        assertNull(recordedTime)
    }

    private object FailingRepository : ScheduleRepository {
        override fun loadCached(): ScheduleSnapshot? = null

        override fun refresh(credentials: Credentials): ScheduleSnapshot {
            throw IllegalStateException("refresh failed")
        }
    }

    private object SuccessfulRepository : ScheduleRepository {
        override fun loadCached(): ScheduleSnapshot? = null

        override fun refresh(credentials: Credentials): ScheduleSnapshot = SNAPSHOT
    }

    private companion object {
        val SNAPSHOT = ScheduleSnapshot(
            termID = "2026-2027-1",
            termStartDate = "2026-09-07",
            fetchedAt = "2026-09-12T00:00:00Z",
            courses = emptyList(),
        )
    }
}
