package com.bupt.schedule.data.remote

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TokenManager(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()

    @Volatile
    private var currentToken: String? = null

    @Volatile
    private var expiresAtMs: Long = 0L

    fun getToken(): String? = currentToken

    fun getExpiresAtMs(): Long = expiresAtMs

    fun isTokenValid(safetyMarginMs: Long = DEFAULT_SAFETY_MARGIN_MS): Boolean {
        val token = currentToken
        if (token.isNullOrBlank() || expiresAtMs <= 0L) return false
        return clock() + safetyMarginMs < expiresAtMs
    }

    fun setToken(token: String, customExpiresAtMs: Long? = null) {
        val trimmed = token.trim()
        currentToken = trimmed
        expiresAtMs = customExpiresAtMs
            ?: parseJwtExpirationMs(trimmed)
            ?: (clock() + DEFAULT_TOKEN_TTL_MS)
    }

    fun invalidateToken() {
        currentToken = null
        expiresAtMs = 0L
    }

    fun startNewSession(authenticate: () -> String): String = lock.withLock {
        invalidateToken()
        val newToken = authenticate().trim()
        setToken(newToken)
        newToken
    }

    fun getOrRenewToken(
        failedToken: String? = null,
        authenticate: () -> String,
    ): String {
        if (failedToken == null) {
            val current = currentToken
            if (current != null) return current
        }

        return lock.withLock {
            val current = currentToken
            if (failedToken != null && current != null && current != failedToken) {
                return@withLock current
            }
            if (failedToken == null && current != null) {
                return@withLock current
            }

            val newToken = authenticate().trim()
            setToken(newToken)
            newToken
        }
    }

    fun getValidToken(
        forceRefresh: Boolean = false,
        authenticate: () -> String,
    ): String {
        if (!forceRefresh && isTokenValid()) {
            val cached = currentToken
            if (cached != null) return cached
        }

        return lock.withLock {
            if (!forceRefresh && isTokenValid()) {
                val cached = currentToken
                if (cached != null) return cached
            }
            val newToken = authenticate().trim()
            setToken(newToken)
            newToken
        }
    }

    companion object {
        const val DEFAULT_SAFETY_MARGIN_MS = 5 * 60 * 1000L // 5 minutes
        const val DEFAULT_TOKEN_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours

        fun parseJwtExpirationMs(jwt: String): Long? = runCatching {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payloadBytes = decodeBase64Url(parts[1])
            val json = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
            if (!json.has("exp")) return null
            val exp = json.getLong("exp")
            if (exp < 100_000_000_000L) exp * 1000L else exp
        }.getOrNull()

        internal fun decodeBase64Url(input: String): ByteArray {
            val clean = input.trimEnd('=')
            val len = clean.length
            val out = ByteArray(len * 3 / 4)
            var outIdx = 0
            var buf = 0
            var bits = 0

            for (i in 0 until len) {
                val c = clean[i].code
                val v = when (c.toChar()) {
                    in 'A'..'Z' -> c - 'A'.code
                    in 'a'..'z' -> c - 'a'.code + 26
                    in '0'..'9' -> c - '0'.code + 52
                    '-', '+' -> 62
                    '_', '/' -> 63
                    else -> continue
                }
                buf = (buf shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    if (outIdx < out.size) {
                        out[outIdx++] = ((buf shr bits) and 0xff).toByte()
                    }
                }
            }
            return if (outIdx == out.size) out else out.copyOf(outIdx)
        }
    }
}
