package com.androidperformancestudio.memory.hprof

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HprofRecordIndexerTest {
    @Test
    fun `indexes top-level records without parsing heap objects`() {
        val directory = createTempDirectory("hprof-index")
        val source = directory.resolve("sample.hprof")
        val indexFile = directory.resolve("cache/sample.idx")
        Files.write(
            source,
            HprofFixtureBuilder()
                .string(1, "Sample")
                .loadClass(2, 1)
                .heapDump(HprofFixtureBuilder().classDump(2, 24))
                .build(),
        )

        val index = HprofRecordIndexer().build(source, indexFile)

        assertTrue(Files.isRegularFile(indexFile))
        assertEquals(4, index.idSize)
        assertEquals(listOf(0x01, 0x02, 0x0c), index.records.map { it.tag })
        assertEquals(index, HprofRecordIndexer().read(indexFile))
    }
}
