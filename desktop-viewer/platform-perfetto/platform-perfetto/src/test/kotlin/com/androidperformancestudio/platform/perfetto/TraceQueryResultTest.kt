package com.androidperformancestudio.platform.perfetto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

public class TraceQueryResultTest {
    @Test
    public fun `ignores trace processor leading blank lines`() {
        val result = TraceQueryResult.parse("\n\n\"name\"\n\"surfaceflinger_layer\"\n")
        assertEquals(listOf("name"), result.columns)
        assertEquals("surfaceflinger_layer", result.rows.single().string("name"))
    }
    @Test
    public fun `parses typed CSV columns including commas quotes and nulls`() {
        val result = TraceQueryResult.parse("\"id\",\"name\",\"ratio\",\"missing\"\n7,\"a, \"\"quoted\"\" name\",1.5,\"[NULL]\"\n")
        val row = result.rows.single()

        assertEquals(7, row.long("id"))
        assertEquals("a, \"quoted\" name", row.string("name"))
        assertEquals(1.5, row.double("ratio"))
        assertNull(row.string("missing"))
    }
}
