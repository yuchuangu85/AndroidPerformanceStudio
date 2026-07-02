package dev.agentperf.android.core

import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.CaptureFrameCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentRequestHandlerTest {
    @Test
    fun `authorized ping retains the version handshake`() {
        val output = ByteArrayOutputStream()
        val handler = handler()

        handler.handle("PING secret", output)

        assertEquals("PONG 1.0\n", output.toString())
    }

    @Test
    fun `authorized capture writes one framed response`() {
        val expected = CaptureFrame("""{"frame":1}""", byteArrayOf(1, 2, 3))
        val output = ByteArrayOutputStream()
        val handler = handler(expected)

        handler.handle("CAPTURE secret", output)
        val actual = CaptureFrameCodec().read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(expected.snapshotJson, actual.snapshotJson)
        assertArrayEquals(expected.screenshotPng, actual.screenshotPng)
    }

    @Test
    fun `invalid token is rejected without invoking capture`() {
        var captureCalls = 0
        val output = ByteArrayOutputStream()
        val handler = AgentRequestHandler("secret") {
            captureCalls += 1
            CaptureFrame("{}", byteArrayOf())
        }

        handler.handle("CAPTURE wrong", output)

        assertEquals("ERROR UNAUTHORIZED Invalid session token\n", output.toString())
        assertEquals(0, captureCalls)
    }

    @Test
    fun `capture failures use stable error codes`() {
        val output = ByteArrayOutputStream()
        val handler = AgentRequestHandler("secret") {
            throw CaptureUnavailableException("NO_ACTIVITY", "No resumed activity")
        }

        handler.handle("CAPTURE secret", output)

        assertEquals("ERROR NO_ACTIVITY No resumed activity\n", output.toString())
    }

    private fun handler(
        frame: CaptureFrame = CaptureFrame("{}", byteArrayOf()),
    ) = AgentRequestHandler("secret") { frame }
}
