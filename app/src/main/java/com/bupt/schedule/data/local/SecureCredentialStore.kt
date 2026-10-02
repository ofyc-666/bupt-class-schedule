// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nemoyuzx/where_to_study SecureCredentialStore.kt at commit 4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50.
package com.bupt.schedule.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object CredentialJsonCodec {
    fun encode(credentials: Credentials): JSONObject = JSONObject()
        .put("account", credentials.account)
        .put("password", credentials.password)
        .apply {
            if (!credentials.teachingCloudPassword.isNullOrEmpty()) {
                put("teachingCloudPassword", credentials.teachingCloudPassword)
            }
        }

    fun decode(jsonString: String): Credentials {
        val value = JSONObject(jsonString)
        val account = value.optString("account")
        val password = value.optString("password")
        val teachingCloudPassword = if (value.has("teachingCloudPassword")) {
            value.optString("teachingCloudPassword").takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return Credentials(account, password, teachingCloudPassword)
    }
}

/** SharedPreferences 中仅保存 AES-GCM IV 与密文；AES 密钥只存在 Android Keystore。 */
class SecureCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(credentials: Credentials) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val payload = CredentialJsonCodec.encode(credentials)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            val ciphertext = try {
                cipher.doFinal(payload)
            } finally {
                payload.fill(0)
            }
            val committed = preferences.edit()
                .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(PAYLOAD_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
            if (!committed) error("commit failed")
        } catch (error: ScheduleException) {
            throw error
        } catch (error: Exception) {
            throw ScheduleException(
                ScheduleFailureKind.LOCAL_STORAGE,
                "无法安全保存本地账号信息。",
                cause = error,
            )
        }
    }

    fun load(): Credentials? {
        val encodedIv = preferences.getString(IV_KEY, null) ?: return null
        val encodedPayload = preferences.getString(PAYLOAD_KEY, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(Base64.decode(encodedPayload, Base64.NO_WRAP))
            try {
                CredentialJsonCodec.decode(String(plaintext, StandardCharsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().clear().commit()
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "bupt_class_schedule.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES_NAME = "secure_credentials_v1"
        const val IV_KEY = "iv"
        const val PAYLOAD_KEY = "payload"
    }
}
