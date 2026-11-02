package com.androidperformancestudio.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MacOsKeychainCredentialStoreTest {
    @Test
    fun `only an absent Keychain item is treated as no credential`() {
        assertNull(MacOsKeychainCredentialStore { 44 to "not found" }.read("fixture"))
        assertEquals("fixture-secret", MacOsKeychainCredentialStore { 0 to "fixture-secret\n" }.read("fixture"))
        for (status in listOf(-1, 1, 36, 51, 128)) {
            val store = MacOsKeychainCredentialStore { status to "private diagnostic" }
            val failure = assertFailsWith<IllegalStateException> { store.read("fixture") }
            assertEquals("E_CREDENTIAL_READ", failure.message)
            assertFalse(failure.toString().contains("private"))
            assertNull(failure.cause)
        }
    }

    @Test
    fun `failed Keychain deletion is observable by connection rollback`() {
        for (status in listOf(0, 44)) MacOsKeychainCredentialStore { status to "" }.delete("fixture")
        for (status in listOf(-1, 1, 36, 51, 128)) {
            val store = MacOsKeychainCredentialStore { status to "private diagnostic" }
            val failure = assertFailsWith<IllegalStateException> { store.delete("fixture") }
            assertEquals("E_CREDENTIAL_DELETE", failure.message)
            assertFalse(failure.toString().contains("private"))
        }
    }

    @Test
    fun `failed writes expose a stable error without the credential or command output`() {
        val store = MacOsKeychainCredentialStore { 36 to "private fixture-secret" }
        val failure = assertFailsWith<IllegalStateException> { store.write("fixture", "fixture-secret") }
        assertEquals("E_CREDENTIAL_WRITE", failure.message)
        assertFalse(failure.toString().contains("fixture-secret"))
    }
}
