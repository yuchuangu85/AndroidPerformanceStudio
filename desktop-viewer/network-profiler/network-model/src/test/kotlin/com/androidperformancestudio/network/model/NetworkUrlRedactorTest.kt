package com.androidperformancestudio.network.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkUrlRedactorTest {
    private val redactor = NetworkUrlRedactor.default()

    @Test
    fun `redacts credentials query values and fragments`() {
        val raw = "https://user:password@example.test:8443/path?token=secret&lang=zh#callback-secret"
        val redacted = redactor.redact(raw)
        assertEquals("https://<redacted>@example.test:8443/path?token=<redacted>&lang=<redacted>", redacted)
        assertFalse(redacted.contains("user"))
        assertFalse(redacted.contains("password"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("callback"))
    }

    @Test
    fun `redacts query flags and returns a safe fallback for invalid URLs`() {
        assertEquals(
            "https://[2001:db8::1]/path?flag=<redacted>&empty=<redacted>",
            redactor.redact("https://[2001:db8::1]/path?flag&empty="),
        )
        assertEquals("redacted://invalid-url", redactor.redact("not a URL with secret"))
    }

    @Test
    fun `whitelists allowed query keys while still blocking sensitive keys`() {
        val allowlistRedactor = NetworkUrlRedactor(queryKeyAllowlist = setOf("page", "size", "lang"))
        val raw = "https://example.test/api?page=1&size=50&lang=zh&token=secret&auth=abc"
        val result = allowlistRedactor.redact(raw)
        assertEquals("https://example.test/api?page=1&size=50&lang=zh&token=<redacted>&auth=<redacted>", result)
        // token and auth should be redacted even though they might be allowlisted
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("abc"))
    }

    @Test
    fun `warns when sensitive key is in allowlist`() {
        val allowlistRedactor = NetworkUrlRedactor(queryKeyAllowlist = setOf("token", "page"))
        allowlistRedactor.redact("https://example.test?token=secret&page=1")
        assertTrue(allowlistRedactor.redactionWarnings.any { it.contains("token") })
    }

    @Test
    fun `parameterizes dynamic path segments`() {
        val paramRedactor = NetworkUrlRedactor(parameterizePath = true)
        assertEquals(
            "https://example.test/users/{id}/orders/{id}/items",
            paramRedactor.redact("https://example.test/users/12345/orders/67890/items"),
        )
        assertEquals(
            "https://example.test/item/{id}-detail",
            paramRedactor.redact("https://example.test/item/550e8400-e29b-41d4-a716-446655440000-detail"),
        )
    }

    @Test
    fun `preserves non-dynamic path segments`() {
        val paramRedactor = NetworkUrlRedactor(parameterizePath = true)
        assertEquals(
            "https://example.test/api/v2/users",
            paramRedactor.redact("https://example.test/api/v2/users"),
        )
    }
}
