package com.androidperformancestudio.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class ComposeAgentRequestLifecycleTest {
    @Test
    fun `old confirmation cannot revoke or authorize the replacement after suspended verification`() =
        runBlocking {
            for (targetCurrent in listOf(false, true)) {
                val oldRequest = Any()
                var pending: Any? = oldRequest
                val verification = CompletableDeferred<Boolean>()
                var authorized = 0
                var revoked = 0
                val oldConfirmation =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        confirmComposeAgentRequest(
                            isCurrentRequest = { pending === oldRequest },
                            verifyTarget = { verification.await() },
                            onTargetChanged = { revoked++ },
                            onAuthorized = { authorized++ },
                        )
                    }
                assertFalse(oldConfirmation.isCompleted)
                pending = Any()
                verification.complete(targetCurrent)
                oldConfirmation.join()
                assertEquals(0, authorized)
                assertEquals(0, revoked)
            }
        }

    @Test
    fun `only the current consent checks and acts on target availability`() =
        runBlocking {
            var verifications = 0
            var authorized = 0
            var revoked = 0

            suspend fun confirm(
                current: Boolean,
                targetCurrent: Boolean,
            ) {
                confirmComposeAgentRequest(
                    isCurrentRequest = { current },
                    verifyTarget = {
                        verifications++
                        targetCurrent
                    },
                    onTargetChanged = { revoked++ },
                    onAuthorized = { authorized++ },
                )
            }
            confirm(current = false, targetCurrent = true)
            assertEquals(0, verifications)
            confirm(current = true, targetCurrent = false)
            confirm(current = true, targetCurrent = true)
            assertEquals(2, verifications)
            assertEquals(1, revoked)
            assertEquals(1, authorized)
        }

    @Test
    fun `cancelled full refresh can retry the same click compatibly exactly once`() =
        runBlocking {
            val requests = ManualRefreshRequests()
            var captures = 0
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val attempt = requireNotNull(requests.begin(1))
                    try {
                        awaitCancellation()
                    } finally {
                        requests.finish(attempt, cancelled = true)
                    }
                }
            first.cancelAndJoin()

            val compatible = requireNotNull(requests.begin(1))
            captures++
            assertTrue(requests.finish(compatible, cancelled = false))
            assertNull(requests.begin(1))
            assertEquals(1, captures)
        }

    @Test
    fun `older cancelled attempt cannot clear replacement progress`() {
        val requests = ManualRefreshRequests()
        val old = requireNotNull(requests.begin(1))
        val replacement = requireNotNull(requests.begin(1))
        assertFalse(requests.finish(old, cancelled = true))
        assertTrue(requests.finish(replacement, cancelled = false))
        assertNull(requests.begin(1))
    }

    @Test
    fun `automatic mode target changes and archive import discard pending clicks without replay`() {
        val requests = ManualRefreshRequests()
        assertNull(requests.begin(0))
        for (request in 1..3) {
            val interrupted = requireNotNull(requests.begin(request))
            requests.discardThrough(request)
            assertFalse(requests.finish(interrupted, cancelled = true))
            assertNull(requests.begin(request))
        }
        assertNotNull(requests.begin(4))
    }
}
