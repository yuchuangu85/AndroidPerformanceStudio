package com.androidperformancestudio.contracts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArtifactFileEvidenceTest {
    @Test
    fun `hashes immutable artifact bytes rather than their locator`() {
        val directory = Files.createTempDirectory("artifact-evidence")
        val original = directory.resolve("original.trace")
        val moved = directory.resolve("moved.trace")
        Files.writeString(original, "immutable evidence")
        Files.move(original, moved)

        assertEquals(
            Sha256("762eae78c6f230f3e1ebea889e8e57ef06b80a5cf3a062e19a6dd569dbe772f9"),
            ArtifactFileEvidence.sha256(moved),
        )
    }

    @Test
    fun `persists an installation local salt without exposing a device serial`() {
        val saltFile = Files.createTempDirectory("device-salt").resolve("identity.salt")
        val store = DeviceIdentitySaltStore(saltFile, randomBytes = { ByteArray(32) { 7 } })

        val first = store.loadOrCreate()
        val second = store.loadOrCreate()
        val identity = DeviceLocalId.fromRawSerial("sensitive-serial", first)

        assertEquals(first.toList(), second.toList())
        assertEquals(32, first.size)
        assertFalse(identity.value.contains("sensitive-serial"))
    }

    @Test
    fun `all profilers can share one installation pseudonym without rehashing persisted ids`() {
        val pseudonymizer = DeviceIdentityPseudonymizer(ByteArray(32) { 9 })
        val identity = pseudonymizer.localId("device-serial")

        assertEquals(identity, pseudonymizer.localId("device-serial"))
        assertEquals(identity, pseudonymizer.localId(identity.value))
    }
}
