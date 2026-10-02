package com.bupt.schedule.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TokenManagerTest {
    // Fake JWT with payload: {"aud":"test-user","exp":2000000000000,"iat":1999990000000}
    // Base64Url header: eyJhbGciOiJIUzUxMiJ9
    // Base64Url payload: eyJhdWQiOiJ0ZXN0LXVzZXIiLCJleHAiOjIwMDAwMDAwMDAwMDAsImlhdCI6MTk5OTk5MDAwMDAwMH0
    private val fakeJwt =
        "eyJhbGciOiJIUzUxMiJ9.eyJhdWQiOiJ0ZXN0LXVzZXIiLCJleHAiOjIwMDAwMDAwMDAwMDAsImlhdCI6MTk5OTk5MDAwMDAwMH0.fakeSignature"

    @Test
    fun parsesJwtExpirationInMilliseconds() {
        val exp = TokenManager.parseJwtExpirationMs(fakeJwt)
        assertEquals(2000000000000L, exp)
    }

    @Test
    fun parsesJwtExpirationInSecondsAndConvertsToMillis() {
        // {"exp": 1789319200}
        // Base64Url for {"exp":1789319200} is eyJleHAiOjE3ODkzMTkyMDB9
        val token = "header.eyJleHAiOjE3ODkzMTkyMDB9.signature"
        val exp = TokenManager.parseJwtExpirationMs(token)
        assertEquals(1789319200000L, exp)
    }

    @Test
    fun tokenValidityRespectsSafetyMargin() {
        var currentTime = 2000000000000L - 10 * 60 * 1000L // 10 minutes before exp
        val manager = TokenManager(clock = { currentTime })

        assertFalse(manager.isTokenValid())

        manager.setToken(fakeJwt)
        assertTrue(manager.isTokenValid(safetyMarginMs = 5 * 60 * 1000L))

        // Advance to 4 minutes before exp (inside 5 min safety margin)
        currentTime = 2000000000000L - 4 * 60 * 1000L
        assertFalse(manager.isTokenValid(safetyMarginMs = 5 * 60 * 1000L))

        // Invalidate
        manager.invalidateToken()
        assertNull(manager.getToken())
        assertFalse(manager.isTokenValid())
    }

    @Test
    fun getValidTokenReusesCachedTokenWithoutCallingAuthenticate() {
        var authCount = 0
        val currentTime = 2000000000000L - 10 * 60 * 1000L
        val manager = TokenManager(clock = { currentTime })

        val token1 = manager.getValidToken {
            authCount++
            fakeJwt
        }
        assertEquals(fakeJwt, token1)
        assertEquals(1, authCount)

        // Second call should return cached token without calling authenticate
        val token2 = manager.getValidToken {
            authCount++
            "new-token"
        }
        assertEquals(fakeJwt, token2)
        assertEquals(1, authCount)

        // Force refresh should invoke authenticate
        val token3 = manager.getValidToken(forceRefresh = true) {
            authCount++
            fakeJwt
        }
        assertEquals(fakeJwt, token3)
        assertEquals(2, authCount)
    }

    @Test
    fun startNewSessionAlwaysFetchesFreshToken() {
        val manager = TokenManager()
        var authCount = 0
        manager.setToken("previous-token")

        val token1 = manager.startNewSession {
            authCount++
            "fresh-token-1"
        }
        assertEquals("fresh-token-1", token1)
        assertEquals("fresh-token-1", manager.getToken())
        assertEquals(1, authCount)

        val token2 = manager.startNewSession {
            authCount++
            "fresh-token-2"
        }
        assertEquals("fresh-token-2", token2)
        assertEquals("fresh-token-2", manager.getToken())
        assertEquals(2, authCount)
    }

    @Test
    fun singleFlightConcurrent401RetryAuthenticatesOnlyOnce() {
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        val authCount = AtomicInteger(0)
        val manager = TokenManager()
        val oldToken = "token-old-A"
        val newToken = "token-new-B"
        manager.setToken(oldToken)

        val results = ConcurrentLinkedQueue<String>()

        for (i in 0 until threadCount) {
            executor.submit {
                readyLatch.countDown()
                startLatch.await()
                val token = manager.getOrRenewToken(failedToken = oldToken) {
                    authCount.incrementAndGet()
                    Thread.sleep(50) // simulate network delay
                    newToken
                }
                results.add(token)
                doneLatch.countDown()
            }
        }

        readyLatch.await()
        startLatch.countDown()
        doneLatch.await()
        executor.shutdown()

        assertEquals(1, authCount.get())
        assertEquals(10, results.size)
        results.forEach { token ->
            assertEquals(newToken, token)
        }
        assertEquals(newToken, manager.getToken())
    }

    @Test
    fun getOrRenewTokenReusesSessionTokenWhenFailedTokenIsNull() {
        val manager = TokenManager()
        var authCount = 0
        manager.setToken("session-token")

        val token1 = manager.getOrRenewToken(failedToken = null) {
            authCount++
            "should-not-be-called"
        }
        assertEquals("session-token", token1)
        assertEquals(0, authCount)
    }
}
