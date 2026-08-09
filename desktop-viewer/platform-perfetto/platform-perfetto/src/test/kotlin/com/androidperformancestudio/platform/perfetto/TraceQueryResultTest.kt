package com.androidperformancestudio.platform.perfetto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

public class TraceQueryResultTest {
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
