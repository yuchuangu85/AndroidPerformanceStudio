package com.androidperformancestudio.desktop

import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DesktopAiBootstrapTest {
    @Test
    fun `cancelled IO delivery releases the acquired runtime profile`() = runBlocking {
        val root = Files.createTempDirectory("aps-ai-delivery-test-").toRealPath()
        val acquired = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            val opening = async {
                acquireDesktopAiRuntime {
                    open(root).also {
                        acquired.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            try {
                withTimeout(10_000) { acquired.await() }
                opening.cancel()
            } finally {
                release.countDown()
            }
            withTimeout(10_000) { opening.join() }
            assertTrue(opening.isCancelled)
            open(root).use { assertTrue(it.history.list().isEmpty()) }
        } finally {
            release.countDown()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `successful IO delivery transfers runtime ownership to the caller`() = runBlocking {
        val root = Files.createTempDirectory("aps-ai-delivery-success-").toRealPath()
        try {
            val runtime = acquireDesktopAiRuntime { open(root) }
            runtime.use {
                assertThrows<IllegalStateException> { open(root) }
            }
            open(root).close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup failure preserves the original exception`() = runBlocking {
        val failure = IllegalStateException("fixture")
        val actual = assertThrows<IllegalStateException> { acquireDesktopAiRuntime { throw failure } }
        assertEquals(failure.message, actual.message)
        // Coroutine stacktrace recovery may clone an exception while retaining its original cause.
        assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === failure })
    }

    private fun open(root: java.nio.file.Path) = AiPlatformRuntime.open(
        root.resolve("profile"),
        openAiAvailable = { false },
        openAiGateway = { error("No implicit provider request") },
    )
}
