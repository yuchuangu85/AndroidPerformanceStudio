package com.androidperformancestudio.contracts

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/** Evidence operations whose result depends on artifact bytes rather than a mutable locator. */
object ArtifactFileEvidence {
    fun sha256(path: Path): Sha256 {
        require(Files.isRegularFile(path)) { "artifact content is not a regular file: $path" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return Sha256(HexFormat.of().formatHex(digest.digest()))
    }
}

private const val HASH_BUFFER_SIZE = 1024 * 1024

/** Keeps device pseudonyms installation-local instead of stable across installations. */
class DeviceIdentitySaltStore(
    private val saltFile: Path = defaultSaltFile(),
    private val randomBytes: () -> ByteArray = ::secureSalt,
) {
    fun loadOrCreate(): ByteArray {
        if (Files.isRegularFile(saltFile)) return readSalt()
        Files.createDirectories(requireNotNull(saltFile.parent) { "device identity salt needs a parent directory" })
        val salt = randomBytes()
        require(salt.size >= MINIMUM_SALT_BYTES) { "device identity salt must contain at least $MINIMUM_SALT_BYTES bytes" }
        val temporary = Files.createTempFile(saltFile.parent, "device-identity", ".salt.tmp")
        try {
            Files.write(temporary, salt)
            try {
                Files.move(temporary, saltFile, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                return readSalt()
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                try {
                    Files.move(temporary, saltFile)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    return readSalt()
                }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return salt.copyOf()
    }

    private fun readSalt(): ByteArray =
        Files.readAllBytes(saltFile).also { salt ->
            require(salt.size >= MINIMUM_SALT_BYTES) { "stored device identity salt is invalid" }
        }

    companion object {
        private const val MINIMUM_SALT_BYTES = 16

        fun defaultSaltFile(): Path =
            Path.of(System.getProperty("user.home"), ".android-performance-studio", "device-identity.salt")

        private fun secureSalt(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}

/** Produces the same installation-local device pseudonym in every profiler. */
class DeviceIdentityPseudonymizer(
    private val applicationSalt: ByteArray = DeviceIdentitySaltStore().loadOrCreate(),
) {
    init {
        require(applicationSalt.size >= MINIMUM_SALT_BYTES) {
            "device identity salt must contain at least $MINIMUM_SALT_BYTES bytes"
        }
    }

    fun localId(rawSerialOrLocalId: String): DeviceLocalId =
        if (LOCAL_ID.matches(rawSerialOrLocalId)) {
            DeviceLocalId.fromPersisted(rawSerialOrLocalId)
        } else {
            DeviceLocalId.fromRawSerial(rawSerialOrLocalId, applicationSalt)
        }

    private companion object {
        const val MINIMUM_SALT_BYTES = 16
        val LOCAL_ID: Regex = Regex("[0-9a-f]{64}")
    }
}
