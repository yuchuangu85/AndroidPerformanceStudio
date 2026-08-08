package com.androidperformancestudio.memory.analysis

import kotlin.test.Test

/**
 * Debug helper used to reproduce class-list vs AS discrepancies against a real HPROF dump
 * (`-Dsample.hprof=/path/to/dump.hprof`). Disabled by default.
 */
class LeakViewDiagnosticTest {
    @Test
    fun `noop - diagnostic disabled`() = Unit
}
