// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study ScheduleClient.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.data.remote

import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object SjdInputLimits {
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
}

internal object SjdResponseReader {
    fun read(stream: InputStream?, declaredLength: Long): String {
        if (declaredLength > SjdInputLimits.MAX_RESPONSE_BYTES) invalidSize()
        if (stream == null) return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > SjdInputLimits.MAX_RESPONSE_BYTES) invalidSize()
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun invalidSize(): Nothing = throw ScheduleException(
        ScheduleFailureKind.INVALID_RESPONSE,
        "移动教务返回的数据超过大小限制。",
    )
}

internal data class SjdRedirectRequest(val method: String, val preserveBody: Boolean)

internal object SjdRedirectPolicy {
    const val MAX_REDIRECTS = 5
    private val allowedOrigin = URI.create(SjdApiClient.ORIGIN)
    private val redirectStatuses = setOf(301, 302, 303, 307, 308)

    fun isRedirect(status: Int) = status in redirectStatuses

    fun followUp(status: Int, method: String): SjdRedirectRequest {
        val normalized = method.uppercase(Locale.ROOT)
        return when {
            status == 303 && normalized != "HEAD" -> SjdRedirectRequest("GET", false)
            status in setOf(301, 302) && normalized == "POST" -> SjdRedirectRequest("GET", false)
            else -> SjdRedirectRequest(normalized, true)
        }
    }

    fun resolve(current: URI, location: String?, followed: Int): URI {
        if (followed >= MAX_REDIRECTS) throw invalidRedirect("移动教务重定向次数过多。")
        val target = runCatching {
            current.resolve(location?.takeIf(String::isNotBlank) ?: error("missing location"))
        }.getOrElse { throw invalidRedirect("移动教务返回了无效的重定向地址。") }
        if (!isAllowed(target)) throw invalidRedirect("移动教务拒绝了不安全的重定向。")
        return target
    }

    private fun isAllowed(target: URI): Boolean =
        target.scheme.equals("https", ignoreCase = true) &&
            target.host.equals(allowedOrigin.host, ignoreCase = true) &&
            target.userInfo == null &&
            effectivePort(target) == effectivePort(allowedOrigin) &&
            (target.port != -1 || target.rawAuthority?.equals(target.host, ignoreCase = true) == true)

    private fun effectivePort(uri: URI) = if (uri.port == -1) 443 else uri.port

    private fun invalidRedirect(message: String) =
        ScheduleException(ScheduleFailureKind.INVALID_RESPONSE, message)
}

class SjdApiClient(
    val tokenManager: TokenManager = TokenManager(),
) {
    fun login(credentials: Credentials): String {
        val account = credentials.account.trim()
        if (account.isEmpty() || credentials.password.isEmpty()) {
            throw ScheduleException(ScheduleFailureKind.EMPTY_CREDENTIALS, "请输入学号和密码。")
        }
        val payload = post(
            path = "/bjyddx/login",
            referer = LOGIN_REFERER,
            form = mapOf("userNo" to account, "pwd" to credentials.password),
        )
        if (!isSuccessful(payload)) {
            throw ScheduleException(ScheduleFailureKind.LOGIN_FAILED, "登录失败，请检查学号和密码。")
        }
        val token = payload.optJSONObject("data")?.optString("token").orEmpty().trim()
            .ifEmpty {
                throw ScheduleException(ScheduleFailureKind.MISSING_TOKEN, "登录成功，但服务端没有返回 token。")
            }
        tokenManager.setToken(token)
        return token
    }

    fun post(
        path: String,
        referer: String,
        form: Map<String, String> = emptyMap(),
        token: String? = null,
    ): JSONObject = request("POST", path, referer, form, token)

    fun postAuthenticated(
        path: String,
        referer: String,
        form: Map<String, String> = emptyMap(),
        credentialsProvider: () -> Credentials?,
    ): JSONObject {
        fun acquireToken(failedToken: String?): String = tokenManager.getOrRenewToken(failedToken = failedToken) {
            val creds = credentialsProvider()
                ?: throw ScheduleException(ScheduleFailureKind.EMPTY_CREDENTIALS, "\u7f3a\u5c11\u767b\u5f55\u51ed\u636e\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55\u3002")
            login(creds)
        }

        val initialToken = acquireToken(failedToken = null)
        return try {
            val response = post(path, referer, form, token = initialToken)
            if (response.opt("code").stringValue() == "401") {
                val freshToken = acquireToken(failedToken = initialToken)
                post(path, referer, form, token = freshToken)
            } else {
                response
            }
        } catch (error: ScheduleException) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                val freshToken = acquireToken(failedToken = initialToken)
                post(path, referer, form, token = freshToken)
            } else {
                throw error
            }
        }
    }

    fun isSuccessful(payload: JSONObject): Boolean = payload.opt("code").stringValue() == "1"

    private fun request(
        method: String,
        path: String,
        referer: String,
        form: Map<String, String>,
        token: String?,
    ): JSONObject {
        var target = URI.create("$ORIGIN$path")
        var requestMethod = method
        var requestForm = form
        var redirects = 0

        while (true) {
            var connection: HttpURLConnection? = null
            try {
                connection = target.toURL().openConnection() as HttpURLConnection
                connection.requestMethod = requestMethod
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.doInput = true
                connection.setRequestProperty("Origin", ORIGIN)
                connection.setRequestProperty("Referer", referer)
                connection.setRequestProperty("User-Agent", "BUPT-Class-Schedule/1.0 Android")
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("token", it) }
                if (requestForm.isNotEmpty() && requestMethod !in setOf("GET", "HEAD")) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(formData(requestForm)) }
                }

                val status = connection.responseCode
                if (SjdRedirectPolicy.isRedirect(status)) {
                    target = SjdRedirectPolicy.resolve(target, connection.getHeaderField("Location"), redirects)
                    val followUp = SjdRedirectPolicy.followUp(status, requestMethod)
                    requestMethod = followUp.method
                    if (!followUp.preserveBody) requestForm = emptyMap()
                    redirects += 1
                    continue
                }

                val stream = if (status in 200..399) connection.inputStream else connection.errorStream
                val declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val body = SjdResponseReader.read(stream, declaredLength)
                if (status !in 200..399) {
                    throw ScheduleException(
                        ScheduleFailureKind.HTTP_FAILED,
                        "移动教务请求失败（HTTP $status）。",
                        retryable = status == 408 || status == 429 || status >= 500,
                        statusCode = status,
                    )
                }
                return runCatching { JSONObject(body) }.getOrElse {
                    throw ScheduleException(
                        ScheduleFailureKind.INVALID_RESPONSE,
                        "移动教务返回了无法识别的数据。",
                    )
                }
            } catch (error: ScheduleException) {
                throw error
            } catch (error: IOException) {
                throw ScheduleException(
                    ScheduleFailureKind.NETWORK_UNREACHABLE,
                    "无法连接移动教务，请检查当前网络后重试。",
                    retryable = true,
                    cause = error,
                )
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun formData(values: Map<String, String>): ByteArray = values.entries
        .sortedBy(Map.Entry<String, String>::key)
        .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        .toByteArray(StandardCharsets.UTF_8)

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val ORIGIN = "https://jwglweixin.bupt.edu.cn"
        const val LOGIN_REFERER = "$ORIGIN/sjd/#/login"
        const val SCHEDULE_REFERER = "$ORIGIN/sjd/#/restClassroom"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

internal fun Any?.stringValue(): String = when (this) {
    null, JSONObject.NULL -> ""
    is String -> this
    is Number -> toString()
    else -> toString()
}
