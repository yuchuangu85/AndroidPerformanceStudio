@file:Suppress("SpreadOperator", "TooManyFunctions")

package com.androidperformancestudio.memory.app

import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.FileLockInterruptionException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Immutable content is retained by leases, including consumers in other JVMs. */
internal class ManagedHprofSource(
    private val root: Path,
) {
    class Lease internal constructor(
        val path: Path,
        val digest: String,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    fun acquire(
        source: Path,
        checkpoint: () -> Unit = {},
    ): Lease {
        require(Files.isRegularFile(source) && Files.isReadable(source)) { "HPROF source is unavailable: $source" }
        val digest = sha256(source, checkpoint)
        val canonicalRoot = prepareRoot()
        return guarded(checkpoint) {
            publicationLock(canonicalRoot, checkpoint) {
                val target = canonicalRoot.resolve("$digest.hprof")
                val retained = active[target]
                if (retained != null) {
                    validateWinner(target, source, digest, checkpoint)
                    val lease = lease(retained)
                    retained.references++
                    lease
                } else {
                    acquireNew(canonicalRoot, target, source, digest, checkpoint)
                }
            }
        }
    }

    private fun prepareRoot(): Path {
        Files.createDirectories(root, *creationPermissions(root, DIRECTORY_PERMISSIONS))
        return root.toRealPath().also { restrictPermissions(it, DIRECTORY_PERMISSIONS) }
    }

    @Suppress("NestedBlockDepth")
    private fun acquireNew(
        canonicalRoot: Path,
        target: Path,
        source: Path,
        digest: String,
        checkpoint: () -> Unit,
    ): Lease {
        val channel = openLock(canonicalRoot.resolve("$digest.lock"))
        var published = false
        var shared: FileLock? = null
        var transferred = false
        try {
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                validateWinner(target, source, digest, checkpoint)
            } else {
                // A missing file with a live foreign lease is corruption, not a new cache entry.
                val exclusive = channel.tryLock() ?: throw IOException("Managed HPROF source disappeared while leased")
                exclusive.use {
                    publish(target, source, digest, checkpoint) { published = true }
                }
            }
            checkpoint()
            shared = waitForLock(channel, shared = true, checkpoint)
            val retained = RetainedContent(target, digest, channel, shared)
            val lease = lease(retained)
            active[target] = retained
            transferred = true
            return lease
        } finally {
            if (!transferred) {
                try {
                    shared?.release()
                    if (published) deleteIfUnleased(channel, target)
                } finally {
                    channel.close()
                }
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun publish(
        target: Path,
        source: Path,
        digest: String,
        checkpoint: () -> Unit,
        onPublished: () -> Unit,
    ) {
        val temporary =
            Files.createTempFile(target.parent, ".hprof-", ".tmp", *creationPermissions(root, FILE_PERMISSIONS))
        try {
            restrictPermissions(temporary, FILE_PERMISSIONS)
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(sha256(temporary, checkpoint) == digest && sha256(source, checkpoint) == digest) {
                "HPROF source changed while creating the managed snapshot"
            }
            restrictPermissions(temporary, CONTENT_PERMISSIONS)
            // The root lock serializes publication; createLink additionally cannot replace a winner.
            require(!Files.exists(target, NOFOLLOW_LINKS)) { "Managed HPROF source appeared during publication" }
            Files.createLink(target, temporary)
            onPublished()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validateWinner(
        target: Path,
        source: Path,
        digest: String,
        checkpoint: () -> Unit,
    ) {
        require(Files.isRegularFile(target, NOFOLLOW_LINKS) && sha256(target, checkpoint) == digest) {
            "Managed HPROF source checksum mismatch"
        }
        require(sha256(source, checkpoint) == digest) { "HPROF source changed while opening the snapshot" }
        // Avoid changing source metadata for an unchanged immutable snapshot.
        restrictPermissions(target, CONTENT_PERMISSIONS)
    }

    private class RetainedContent(
        val path: Path,
        val digest: String,
        val channel: FileChannel,
        val shared: FileLock,
        var references: Int = 1,
    )

    companion object {
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val LOCK_POLL_MILLIS = 20L
        private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        private val CONTENT_PERMISSIONS = PosixFilePermissions.fromString("r--------")
        private val guard = ReentrantLock()

        // Entries exist only while this JVM has leases; lock files themselves are never unlinked.
        private val active = mutableMapOf<Path, RetainedContent>()

        fun sha256(
            path: Path,
            checkpoint: () -> Unit = {},
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return java.util.HexFormat
                .of()
                .formatHex(digest.digest())
        }

        private fun lease(retained: RetainedContent): Lease =
            Lease(
                retained.path,
                retained.digest,
            ) { release(retained) }

        @Suppress("NestedBlockDepth")
        private fun release(retained: RetainedContent) {
            // Cleanup must also work when close is called by an already interrupted cancellation path.
            var interrupted = Thread.interrupted()
            guard.lock()
            try {
                check(active[retained.path] === retained) { "Managed HPROF lease ownership mismatch" }
                if (--retained.references != 0) return
                try {
                    while (true) {
                        try {
                            publicationLock(retained.path.parent, {}) {
                                if (retained.shared.isValid) retained.shared.release()
                                deleteIfUnleased(retained.channel, retained.path)
                            }
                            break
                        } catch (_: FileLockInterruptionException) {
                            interrupted = true
                            Thread.interrupted()
                        } catch (_: ClosedByInterruptException) {
                            interrupted = true
                            Thread.interrupted()
                        } catch (_: InterruptedException) {
                            interrupted = true
                            Thread.interrupted()
                        }
                    }
                } finally {
                    active.remove(retained.path)
                    retained.channel.close()
                }
            } finally {
                guard.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun deleteIfUnleased(
            channel: FileChannel,
            target: Path,
        ) {
            if (!channel.isOpen) {
                openLock(target.resolveSibling("${target.fileName.toString().removeSuffix(".hprof")}.lock")).use {
                    deleteIfUnleased(it, target)
                }
                return
            }
            val exclusive = channel.tryLock() ?: return
            exclusive.use { Files.deleteIfExists(target) }
        }

        private fun <T> guarded(
            checkpoint: () -> Unit,
            action: () -> T,
        ): T {
            try {
                while (!guard.tryLock(LOCK_POLL_MILLIS, TimeUnit.MILLISECONDS)) checkpoint()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            try {
                checkpoint()
                return action()
            } finally {
                guard.unlock()
            }
        }

        private fun <T> publicationLock(
            canonicalRoot: Path,
            checkpoint: () -> Unit,
            action: () -> T,
        ): T =
            openLock(canonicalRoot.resolve(".publish.lock")).use { channel ->
                waitForLock(channel, shared = false, checkpoint).use { action() }
            }

        private fun waitForLock(
            channel: FileChannel,
            shared: Boolean,
            checkpoint: () -> Unit,
        ): FileLock {
            while (true) {
                checkpoint()
                val lock = channel.tryLock(0, Long.MAX_VALUE, shared)
                if (lock != null) return lock
                Thread.sleep(LOCK_POLL_MILLIS)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun openLock(path: Path): FileChannel {
            val options = setOf<OpenOption>(CREATE, READ, WRITE, NOFOLLOW_LINKS)
            val channel = FileChannel.open(path, options, *creationPermissions(path, FILE_PERMISSIONS))
            return try {
                restrictPermissions(path, FILE_PERMISSIONS)
                channel
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }

        private fun creationPermissions(
            path: Path,
            permissions: Set<PosixFilePermission>,
        ): Array<FileAttribute<*>> =
            if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                arrayOf(PosixFilePermissions.asFileAttribute(permissions))
            } else {
                emptyArray()
            }

        private fun restrictPermissions(
            path: Path,
            permissions: Set<PosixFilePermission>,
        ) {
            val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            if (posix != null) {
                if (posix.readAttributes().permissions() != permissions) posix.setPermissions(permissions)
            } else {
                val acl =
                    Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
                        ?: throw IOException("Managed HPROF storage cannot enforce owner-only access: $path")
                val ownerOnly =
                    listOf(
                        AclEntry
                            .newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(acl.owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                            .build(),
                    )
                if (acl.acl != ownerOnly) acl.acl = ownerOnly
            }
        }
    }
}
