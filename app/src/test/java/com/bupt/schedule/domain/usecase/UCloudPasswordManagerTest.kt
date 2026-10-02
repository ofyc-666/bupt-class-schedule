package com.bupt.schedule.domain.usecase

import com.bupt.schedule.domain.model.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UCloudPasswordManagerTest {
    private val teaching = Credentials("demo_account_1", "teaching-password", null)

    @Test
    fun failedVerificationNeverSavesPasswordOrChangesTeachingCredentials() {
        var saved = teaching
        val manager = UCloudPasswordManager(
            verify = { throw IOException("login failed") },
            save = { saved = it },
        )
        try {
            manager.configure(teaching, "wrong")
            fail("Expected login failure")
        } catch (_: IOException) {
            assertEquals(teaching, saved)
        }
    }

    @Test
    fun successfulVerificationSavesPasswordWithTeachingCredentials() {
        var saved = teaching
        val manager = UCloudPasswordManager(
            verify = { assertEquals("valid", it.teachingCloudPassword) },
            save = { saved = it },
        )
        manager.configure(teaching, "valid")
        assertEquals("demo_account_1", saved.account)
        assertEquals("teaching-password", saved.password)
        assertEquals("valid", saved.teachingCloudPassword)
    }

    @Test
    fun clearingPasswordKeepsTeachingCredentials() {
        var saved = teaching.copy(teachingCloudPassword = "valid")
        val manager = UCloudPasswordManager(verify = {}, save = { saved = it })
        manager.clear(saved)
        assertEquals(teaching.account, saved.account)
        assertEquals(teaching.password, saved.password)
        assertNull(saved.teachingCloudPassword)
    }

    @Test
    fun accountSwitchDuringVerificationCannotSaveOldAccount() {
        var saved = teaching
        val manager = UCloudPasswordManager(verify = {}, save = { saved = it }, isCurrent = { false })
        try {
            manager.configure(teaching, "valid")
            fail("Expected account switch rejection")
        } catch (_: IllegalStateException) {
            assertEquals(teaching, saved)
        }
    }

    @Test
    fun automaticAndManualRefreshShareSingleFlightGate() {
        val gate = AssignmentRefreshGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            assertTrue(gate.begin())
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            gate.end()
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(gate.begin())
        release.countDown()
        worker.join(2000)
        assertTrue(gate.begin())
        gate.end()
    }
}
