package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SharkLeakAnalyzerTest {
    @Test
    fun `does not invoke shark when no heuristic candidates exist`() {
        val result = SharkLeakAnalyzer().analyze(HeapDump(), Path.of("missing.hprof"))

        assertEquals(LeakCanaryStatus.NO_CANDIDATES, result.status)
        assertEquals(0, result.totalLeaks)
    }
}
