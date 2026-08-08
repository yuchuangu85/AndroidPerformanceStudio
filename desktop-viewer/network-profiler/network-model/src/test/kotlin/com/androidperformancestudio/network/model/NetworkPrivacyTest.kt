package com.androidperformancestudio.network.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkPrivacyTest {
    @Test fun `header values are deny by default`() {
        val headers = NetworkHeaderRedactor.redact(listOf("Authorization" to "secret", "Content-Type" to "application/json"))
        assertEquals("<redacted>", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }
}
