package dev.agentperf.desktop

import dev.agentperf.protocol.CaptureFrame

internal class ReusableForegroundSession<T : AutoCloseable>(
    private val connect: (String?) -> T,
    private val isCurrent: (T) -> Boolean,
    private val capture: (T) -> CaptureFrame,
) : AutoCloseable {
    private var requestedSerial: String? = null
    private var currentSession: T? = null

    val cachedSession: T?
        get() = currentSession

    fun capture(requestedSerial: String?): CaptureFrame {
        val reusable = currentSession
            ?.takeIf { this.requestedSerial == requestedSerial }
            ?.takeIf { session -> runCatching { isCurrent(session) }.getOrDefault(false) }
        val session = reusable ?: reconnect(requestedSerial)
        return capture(session)
    }

    fun invalidate() {
        closeCurrentSession()
    }

    override fun close() {
        closeCurrentSession()
    }

    private fun reconnect(requestedSerial: String?): T {
        closeCurrentSession()
        return connect(requestedSerial).also { session ->
            this.requestedSerial = requestedSerial
            currentSession = session
        }
    }

    private fun closeCurrentSession() {
        currentSession?.let { session ->
            runCatching { session.close() }
        }
        currentSession = null
        requestedSerial = null
    }
}
