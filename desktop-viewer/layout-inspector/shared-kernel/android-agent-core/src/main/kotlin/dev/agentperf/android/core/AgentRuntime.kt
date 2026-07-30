package com.androidperformancestudio.android.core

import java.util.concurrent.atomic.AtomicBoolean

enum class StartResult {
    STARTED,
    ALREADY_RUNNING,
    DISABLED_NOT_DEBUGGABLE,
}

class AgentRuntime(
    private val startTransport: () -> Unit,
) {
    private val running = AtomicBoolean(false)

    fun start(debuggable: Boolean): StartResult {
        if (!debuggable) return StartResult.DISABLED_NOT_DEBUGGABLE
        if (!running.compareAndSet(false, true)) return StartResult.ALREADY_RUNNING
        return try {
            startTransport()
            StartResult.STARTED
        } catch (error: Throwable) {
            running.set(false)
            throw error
        }
    }
}
