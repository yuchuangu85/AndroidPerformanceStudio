package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AutoScanTest {
    @Test
    fun `automatic device scanning is disabled by default`() {
        assertFalse(AUTO_SCAN_DEFAULT_ENABLED)
    }
}
