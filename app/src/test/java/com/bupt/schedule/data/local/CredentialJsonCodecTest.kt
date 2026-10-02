package com.bupt.schedule.data.local

import com.bupt.schedule.domain.model.Credentials
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialJsonCodecTest {

    @Test
    fun testLegacyJsonWithoutTeachingCloudPassword() {
        val legacyJson = JSONObject()
            .put("account", "demo_account_2")
            .put("password", "sjd_password_123")
            .toString()

        val credentials = CredentialJsonCodec.decode(legacyJson)
        assertEquals("demo_account_2", credentials.account)
        assertEquals("sjd_password_123", credentials.password)
        assertNull(credentials.teachingCloudPassword)
    }

    @Test
    fun testJsonWithTeachingCloudPassword() {
        val json = JSONObject()
            .put("account", "demo_account_2")
            .put("password", "sjd_password_123")
            .put("teachingCloudPassword", "ucloud_password_456")
            .toString()

        val credentials = CredentialJsonCodec.decode(json)
        assertEquals("demo_account_2", credentials.account)
        assertEquals("sjd_password_123", credentials.password)
        assertEquals("ucloud_password_456", credentials.teachingCloudPassword)
    }

    @Test
    fun testEmptyTeachingCloudPasswordDoesNotFallBackToEduPassword() {
        val creds = Credentials(
            account = "demo_account_2",
            password = "sjd_password_123",
            teachingCloudPassword = "",
        )
        assertEquals("", creds.teachingCloudPassword)

        val json = JSONObject()
            .put("account", "demo_account_2")
            .put("password", "sjd_password_123")
            .put("teachingCloudPassword", "")
            .toString()

        val decoded = CredentialJsonCodec.decode(json)
        assertNull(decoded.teachingCloudPassword)
    }

    @Test
    fun testEncodeDoesNotIncludeNullOrEmptyTeachingPassword() {
        val creds1 = Credentials("acc", "pwd", null)
        val json1 = CredentialJsonCodec.encode(creds1)
        assertFalse(json1.has("teachingCloudPassword"))

        val creds2 = Credentials("acc", "pwd", "")
        val json2 = CredentialJsonCodec.encode(creds2)
        assertFalse(json2.has("teachingCloudPassword"))

        val creds3 = Credentials("acc", "pwd", "cloudpwd")
        val json3 = CredentialJsonCodec.encode(creds3)
        assertTrue(json3.has("teachingCloudPassword"))
        assertEquals("cloudpwd", json3.getString("teachingCloudPassword"))
    }

    @Test
    fun testTeachingCloudPasswordUpdateKeepsJiaowuPassword() {
        val original = Credentials(
            account = "demo_account_2",
            password = "jiaowu_password_original",
            teachingCloudPassword = null,
        )
        assertNull(original.teachingCloudPassword)

        val updated = original.copy(teachingCloudPassword = "new_ucloud_password")
        assertEquals("demo_account_2", updated.account)
        assertEquals("jiaowu_password_original", updated.password)
        assertEquals("new_ucloud_password", updated.teachingCloudPassword)

        val encoded = CredentialJsonCodec.encode(updated)
        val decoded = CredentialJsonCodec.decode(encoded.toString())
        assertEquals("jiaowu_password_original", decoded.password)
        assertEquals("new_ucloud_password", decoded.teachingCloudPassword)
    }

    @Test
    fun testTeachingCloudPasswordPreservesRawWhitespaceWithoutTrimming() {
        val passwordWithSpaces = "  secret_pass 123  "
        val creds = Credentials(
            account = "demo_account_2",
            password = "jiaowu_password",
            teachingCloudPassword = passwordWithSpaces,
        )
        assertEquals(passwordWithSpaces, creds.teachingCloudPassword)

        val encoded = CredentialJsonCodec.encode(creds)
        val decoded = CredentialJsonCodec.decode(encoded.toString())
        assertEquals(passwordWithSpaces, decoded.teachingCloudPassword)
    }
}
