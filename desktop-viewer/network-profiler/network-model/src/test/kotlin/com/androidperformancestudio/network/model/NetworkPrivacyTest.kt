package com.androidperformancestudio.network.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkPrivacyTest {
    @Test fun `redacts user info path query values and fragment deterministically`() {
        val redactor = NetworkUrlRedactor(ByteArray(32) { 1 })
        val first = redactor.redact("https://user:secret@example.test/users/alice?token=secret&page=2#private")
        val second = redactor.redact("https://example.test/users/alice?token=other")
        assertFalse(first.contains("user"))
        assertFalse(first.contains("alice"))
        assertFalse(first.contains("secret"))
        assertFalse(first.contains("private"))
        assertTrue(first.contains("token=<redacted>"))
        assertEquals(first.substringBefore('?'), second.substringBefore('?'))
    }

    @Test fun `header values are deny by default`() {
        val headers = NetworkHeaderRedactor.redact(listOf("Authorization" to "secret", "Content-Type" to "application/json"))
        assertEquals("<redacted>", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }
}
