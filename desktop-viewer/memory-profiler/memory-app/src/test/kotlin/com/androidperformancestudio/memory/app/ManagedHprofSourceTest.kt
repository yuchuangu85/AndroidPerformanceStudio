@file:Suppress("MagicNumber")

package com.androidperformancestudio.memory.app

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManagedHprofSourceTest {
    @Test
    fun `acquire reuses content and last close reclaims it without touching input`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "immutable source")
            val duplicate = Files.copy(original, root.resolve("duplicate.hprof"))
            val expected = ManagedHprofSource.sha256(original)
            val store = ManagedHprofSource(root.resolve("managed"))
            val first = store.acquire(original)
            val repeated = store.acquire(original)
            val identical = store.acquire(duplicate)
            try {
                assertEquals(expected, first.digest)
                assertEquals(root.resolve("managed/$expected.hprof").toRealPath(), first.path)
                assertNotEquals(original, first.path)
                assertEquals(first.path, repeated.path)
                assertEquals(first.path, identical.path)
                first.close()
                first.close()
                assertTrue(Files.exists(identical.path))
                repeated.close()
                assertTrue(Files.exists(identical.path))
                assertContentEquals(Files.readAllBytes(original), Files.readAllBytes(identical.path))
                identical.close()
                assertFalse(Files.exists(first.path))
                assertTrue(Files.exists(original))
                assertTrue(Files.exists(duplicate))
                assertTrue(Files.exists(root.resolve("managed/.publish.lock")))
                assertTrue(Files.exists(root.resolve("managed/$expected.lock")))
            } finally {
                first.close()
                repeated.close()
                identical.close()
            }
        }

    @Test
    fun `canonical root aliases share one JVM file lock and preserve cache identity`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "same source")
            val managed = Files.createDirectory(root.resolve("managed"))
            val alias = Files.createSymbolicLink(root.resolve("alias"), managed)
            ManagedHprofSource(managed).acquire(original).use { first ->
                val attributes = Files.readAttributes(first.path, BasicFileAttributes::class.java)
                val changeTime =
                    if (first.path.fileSystem
                            .supportedFileAttributeViews()
                            .contains("unix")
                    ) {
                        Files.getAttribute(first.path, "unix:ctime")
                    } else {
                        null
                    }
                ManagedHprofSource(alias).acquire(original).use { second ->
                    assertEquals(first.path, second.path)
                    assertEquals(attributes.fileKey().toString(), fileIdentity(second.path))
                    if (changeTime != null) assertEquals(changeTime, Files.getAttribute(second.path, "unix:ctime"))
                }
                assertTrue(Files.exists(first.path))
            }
            assertTrue(contentFiles(managed).isEmpty())
        }

    @Test
    fun `concurrent JVM acquires reuse one inode and release independently`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "concurrent source")
            val managed = root.resolve("managed")
            val executor = Executors.newFixedThreadPool(4)
            val leases = mutableListOf<ManagedHprofSource.Lease>()
            try {
                val futures =
                    List(16) {
                        executor.submit<ManagedHprofSource.Lease> { ManagedHprofSource(managed).acquire(original) }
                    }
                futures.forEach { leases += it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, leases.map { it.path }.distinct().size)
                assertEquals(1, leases.map { fileIdentity(it.path) }.distinct().size)
                leases.dropLast(1).forEach { it.close() }
                assertTrue(Files.exists(leases.last().path))
                leases.last().close()
                assertTrue(contentFiles(managed).isEmpty())
            } finally {
                executor.shutdownNow()
                leases.forEach { it.close() }
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `concurrent close of one lease does not consume another lease reference`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "source")
            val store = ManagedHprofSource(root.resolve("managed"))
            val first = store.acquire(original)
            store.acquire(original).use { second ->
                val executor = Executors.newFixedThreadPool(4)
                try {
                    List(16) { executor.submit { first.close() } }.forEach { it.get(5, TimeUnit.SECONDS) }
                    assertTrue(Files.exists(second.path))
                } finally {
                    executor.shutdownNow()
                }
            }
            assertFalse(Files.exists(first.path))
        }

    @Test
    fun `changed external content gets a distinct lease and leaves prior content intact`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "first source")
            val store = ManagedHprofSource(root.resolve("managed"))
            store.acquire(original).use { first ->
                Files.writeString(original, "second source")
                store.acquire(original).use { second ->
                    Files.delete(original)
                    assertNotEquals(first.digest, second.digest)
                    assertNotEquals(first.path, second.path)
                    assertEquals("first source", Files.readString(first.path))
                    assertEquals("second source", Files.readString(second.path))
                }
                assertTrue(Files.exists(first.path))
            }
            assertTrue(contentFiles(root.resolve("managed")).isEmpty())
        }

    @Test
    fun `corrupted cached content fails closed without replacing the winner`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "original content")
            val store = ManagedHprofSource(root.resolve("managed"))
            store.acquire(original).use { first ->
                Files.delete(first.path)
                Files.writeString(first.path, "corrupted cache")
                val key = Files.readAttributes(first.path, BasicFileAttributes::class.java).fileKey()
                val error = assertFailsWith<IllegalArgumentException> { store.acquire(original) }
                assertTrue(error.message.orEmpty().contains("checksum mismatch"))
                assertEquals("corrupted cache", Files.readString(first.path))
                assertEquals(key, Files.readAttributes(first.path, BasicFileAttributes::class.java).fileKey())
                assertEquals("original content", Files.readString(original))
            }
        }

    @Test
    fun `preexisting unleased cache winner is validated and reused without replacement`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "cache winner")
            val digest = ManagedHprofSource.sha256(original)
            val managed = Files.createDirectory(root.resolve("managed"))
            val winner = Files.copy(original, managed.resolve("$digest.hprof"))
            val key = Files.readAttributes(winner, BasicFileAttributes::class.java).fileKey()
            ManagedHprofSource(managed).acquire(original).use { lease ->
                assertEquals(winner, lease.path)
                assertEquals(key, Files.readAttributes(lease.path, BasicFileAttributes::class.java).fileKey())
            }
            assertFalse(Files.exists(winner))
        }

    @Test
    fun `cancellation during copy removes temporary content and permits a later acquire`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "cancel source")
            val managed = root.resolve("managed")
            val store = ManagedHprofSource(managed)
            val cancellation = CancellationException("copy cancelled")
            val error =
                assertFailsWith<CancellationException> {
                    store.acquire(original) {
                        if (temporaryFiles(managed).isNotEmpty()) throw cancellation
                    }
                }
            assertSame(cancellation, error)
            assertTrue(contentFiles(managed).isEmpty())
            assertTrue(temporaryFiles(managed).isEmpty())
            store.acquire(original).use { assertTrue(Files.exists(it.path)) }
            assertTrue(contentFiles(managed).isEmpty())
            assertEquals("cancel source", Files.readString(original))
        }

    @Test
    fun `cancellation after publication reclaims content before returning a lease`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "published cancellation")
            val managed = root.resolve("managed")
            val cancellation = CancellationException("published acquisition cancelled")
            val error =
                assertFailsWith<CancellationException> {
                    ManagedHprofSource(managed).acquire(original) {
                        if (contentFiles(managed).isNotEmpty()) throw cancellation
                    }
                }
            assertSame(cancellation, error)
            assertTrue(contentFiles(managed).isEmpty())
            assertTrue(temporaryFiles(managed).isEmpty())
        }

    @Test
    fun `source mutation while copying fails closed and cleans temporary content`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "immutable source")
            val managed = root.resolve("managed")
            var mutated = false
            val error =
                assertFailsWith<IllegalArgumentException> {
                    ManagedHprofSource(managed).acquire(original) {
                        if (!mutated && temporaryFiles(managed).isNotEmpty()) {
                            mutated = true
                            Files.writeString(original, "replacement content")
                        }
                    }
                }
            assertTrue(mutated)
            assertTrue(error.message.orEmpty().contains("source changed"))
            assertTrue(contentFiles(managed).isEmpty())
            assertTrue(temporaryFiles(managed).isEmpty())
            assertEquals("replacement content", Files.readString(original))
        }

    @Test
    fun `close on an interrupted thread reclaims content and preserves interrupt flag`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "source")
            val lease = ManagedHprofSource(root.resolve("managed")).acquire(original)
            try {
                Thread.currentThread().interrupt()
                lease.close()
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
                lease.close()
            }
            assertFalse(Files.exists(lease.path))
        }

    @Test
    fun `interruption during last close waits for publication safely and preserves interrupt`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "close cancellation source")
            val managed = root.resolve("managed")
            val lease = ManagedHprofSource(managed).acquire(original)
            val ready = root.resolve("close-lock-ready")
            val release = root.resolve("close-lock-release")
            val child = child(root, managed, original, ready, release, mode = "publication")
            val failure = AtomicReference<Throwable?>()
            val interrupted = AtomicBoolean()
            var closer: Thread? = null
            try {
                awaitFile(ready, child)
                val worker =
                    thread(isDaemon = true) {
                        try {
                            lease.close()
                            interrupted.set(Thread.currentThread().isInterrupted)
                        } catch (error: Throwable) {
                            failure.set(error)
                        }
                    }
                closer = worker
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.state != Thread.State.TIMED_WAITING && worker.isAlive && System.nanoTime() < deadline) {
                    Thread.sleep(2)
                }
                assertTrue(worker.isAlive)
                worker.interrupt()
                finishChild(child, release)
                worker.join(5000)
                assertFalse(worker.isAlive)
                assertNull(failure.get())
                assertTrue(interrupted.get())
                assertFalse(Files.exists(lease.path))
            } finally {
                finishChild(child, release)
                closer?.join(5000)
                lease.close()
            }
        }

    @Test
    fun `publication lock wait honors cancellation checkpoints`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "source")
            val managed = root.resolve("managed")
            val ready = root.resolve("lock-ready")
            val release = root.resolve("lock-release")
            val child = child(root, managed, original, ready, release, mode = "publication")
            try {
                awaitFile(ready, child)
                val cancellation = CancellationException("publication wait cancelled")
                var polls = 0
                val error =
                    assertFailsWith<CancellationException> {
                        ManagedHprofSource(managed).acquire(original) {
                            if (++polls > 8) throw cancellation
                        }
                    }
                assertSame(cancellation, error)
                assertTrue(contentFiles(managed).isEmpty())
            } finally {
                finishChild(child, release)
            }
        }

    @Test
    fun `a foreign process lease survives local last close and its close reclaims content`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "cross process source")
            val managed = root.resolve("managed")
            val local = ManagedHprofSource(managed).acquire(original)
            val ready = root.resolve("child-ready")
            val release = root.resolve("child-release")
            val child = child(root, managed, original, ready, release)
            try {
                awaitFile(ready, child)
                assertEquals(fileIdentity(local.path), Files.readString(ready))
                local.close()
                assertTrue(Files.exists(local.path))
                finishChild(child, release)
                assertFalse(Files.exists(local.path))
            } finally {
                local.close()
                finishChild(child, release)
            }
        }

    @Test
    fun `concurrent processes publish one inode and never delete a foreign active lease`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "concurrent cross process source")
            val managed = root.resolve("managed")
            val start = root.resolve("start")
            val readyA = root.resolve("ready-a")
            val readyB = root.resolve("ready-b")
            val releaseA = root.resolve("release-a")
            val releaseB = root.resolve("release-b")
            val first = child(root, managed, original, readyA, releaseA, start)
            val second = child(root, managed, original, readyB, releaseB, start)
            try {
                Files.writeString(start, "start")
                awaitFile(readyA, first)
                awaitFile(readyB, second)
                assertEquals(Files.readString(readyA), Files.readString(readyB))
                val target = contentFiles(managed).single()
                finishChild(first, releaseA)
                assertTrue(Files.exists(target))
                finishChild(second, releaseB)
                assertFalse(Files.exists(target))
                assertTrue(Files.exists(managed.resolve(".publish.lock")))
            } finally {
                finishChild(first, releaseA)
                finishChild(second, releaseB)
            }
        }

    @Test
    fun `directory content and lock permissions allow only the owner`() =
        withRoot { root ->
            val original = Files.writeString(root.resolve("original.hprof"), "private source")
            val managed = root.resolve("managed")
            ManagedHprofSource(managed).acquire(original).use { lease ->
                if (Files.getFileAttributeView(managed, PosixFileAttributeView::class.java) != null) {
                    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(managed))
                    assertEquals(
                        PosixFilePermissions.fromString("r--------"),
                        Files.getPosixFilePermissions(lease.path),
                    )
                    assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(managed.resolve(".publish.lock")),
                    )
                    assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(managed.resolve("${lease.digest}.lock")),
                    )
                }
            }
        }

    @Test
    fun `unavailable input does not create managed directories`() =
        withRoot { root ->
            val managed = root.resolve("managed")
            assertFailsWith<IllegalArgumentException> {
                ManagedHprofSource(managed).acquire(root.resolve("missing.hprof"))
            }
            assertFalse(Files.exists(managed))
        }

    @Suppress("LongParameterList")
    private fun child(
        root: Path,
        managed: Path,
        source: Path,
        ready: Path,
        release: Path,
        start: Path = root.resolve("no-start-barrier"),
        mode: String = "lease",
    ): Process {
        val classPath =
            listOf(ManagedHprofSource::class.java, ManagedHprofSourceTest::class.java, Unit::class.java)
                .map {
                    Path
                        .of(
                            it.protectionDomain.codeSource.location
                                .toURI(),
                        ).toString()
                }.distinct()
                .joinToString(File.pathSeparator)
        val java =
            Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java")
        val barrier = if (start.fileName.toString() == "no-start-barrier") "" else start.toString()
        return ProcessBuilder(
            java.toString(),
            "-cp",
            classPath,
            ManagedHprofLeaseTestChild::class.java.name,
            managed.toString(),
            source.toString(),
            ready.toString(),
            release.toString(),
            barrier,
            mode,
        ).redirectErrorStream(true).redirectOutput(root.resolve("${ready.fileName}.log").toFile()).start()
    }

    private fun awaitFile(
        file: Path,
        child: Process,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!Files.exists(file) && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        val log = file.resolveSibling("${file.fileName}.log")
        val diagnostics = if (Files.exists(log)) Files.readString(log) else log.toString()
        assertTrue(Files.exists(file), "Child did not become ready: $diagnostics")
    }

    private fun finishChild(
        child: Process,
        release: Path,
    ) {
        Files.writeString(release, "release")
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Managed source child did not exit")
            assertEquals(0, child.exitValue())
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun contentFiles(root: Path): List<Path> = entries(root, ".hprof")

    private fun temporaryFiles(root: Path): List<Path> = entries(root, ".tmp")

    private fun entries(
        root: Path,
        suffix: String,
    ): List<Path> =
        if (!Files.isDirectory(root)) {
            emptyList()
        } else {
            Files.list(root).use { files ->
                files.filter { it.toString().endsWith(suffix) }.toList()
            }
        }

    private fun withRoot(block: (Path) -> Unit) {
        val root = createTempDirectory("managed-hprof-lease").toRealPath()
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

private fun fileIdentity(path: Path): String =
    Files
        .readAttributes(path, BasicFileAttributes::class.java)
        .fileKey()
        .toString()

/** A real second JVM, independent of Gradle workers and the unfinished Rust sidecar. */
internal object ManagedHprofLeaseTestChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val managed = Path.of(args[0])
        val source = Path.of(args[1])
        val ready = Path.of(args[2])
        val release = Path.of(args[3])
        if (args[4].isNotEmpty()) awaitSignal(Path.of(args[4]))
        if (args[5] == "publication") {
            Files.createDirectories(managed)
            val lockPath = managed.resolve(".publish.lock")
            FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, READ, WRITE).use { channel ->
                channel.lock().use {
                    Files.writeString(ready, "locked")
                    awaitSignal(release)
                }
            }
        } else {
            ManagedHprofSource(managed).acquire(source).use { lease ->
                Files.writeString(ready, fileIdentity(lease.path))
                awaitSignal(release)
            }
        }
    }

    private fun awaitSignal(path: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!Files.exists(path)) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $path" }
            Thread.sleep(10)
        }
    }
}
