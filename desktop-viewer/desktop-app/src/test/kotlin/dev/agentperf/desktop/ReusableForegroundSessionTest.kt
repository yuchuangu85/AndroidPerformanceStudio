package dev.agentperf.desktop

import dev.agentperf.protocol.CaptureFrame
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReusableForegroundSessionTest {
    @Test
    fun `manual refresh reuses the current foreground session for the same requested device`() {
        val sessions = mutableListOf(FakeSession(current = true, frame = frame(1)))
        var connectCount = 0
        val cache = ReusableForegroundSession(
            connect = { requestedSerial ->
                assertEquals("physical-1", requestedSerial)
                connectCount += 1
                sessions.removeFirst()
            },
            isCurrent = FakeSession::isCurrent,
            capture = FakeSession::capture,
        )

        val first = cache.capture("physical-1")
        val second = cache.capture("physical-1")

        assertArrayEquals(byteArrayOf(1), first.screenshotPng)
        assertArrayEquals(byteArrayOf(1), second.screenshotPng)
        assertEquals(1, connectCount)
        assertEquals(2, cache.cachedSession?.captureCalls)
        assertEquals(1, cache.cachedSession?.currentChecks)
        assertEquals(0, cache.cachedSession?.closeCalls)
    }

    @Test
    fun `manual refresh reconnects when the cached session is no longer foreground`() {
        val stale = FakeSession(current = false, frame = frame(1))
        val fresh = FakeSession(current = true, frame = frame(2))
        val sessions = mutableListOf(stale, fresh)
        val cache = ReusableForegroundSession(
            connect = { sessions.removeFirst() },
            isCurrent = FakeSession::isCurrent,
            capture = FakeSession::capture,
        )

        cache.capture("physical-1")
        val second = cache.capture("physical-1")

        assertArrayEquals(byteArrayOf(2), second.screenshotPng)
        assertEquals(1, stale.closeCalls)
        assertEquals(1, fresh.captureCalls)
    }

    private class FakeSession(
        private val current: Boolean,
        private val frame: CaptureFrame,
    ) : AutoCloseable {
        var currentChecks = 0
            private set
        var captureCalls = 0
            private set
        var closeCalls = 0
            private set

        fun isCurrent(): Boolean {
            currentChecks += 1
            return current
        }

        fun capture(): CaptureFrame {
            captureCalls += 1
            return frame
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private fun frame(value: Byte) = CaptureFrame("{}", byteArrayOf(value))
}
