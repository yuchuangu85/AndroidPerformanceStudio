package com.androidperformancestudio.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AiContextPayloadTest {
    @Test
    fun `payload escapes text and preserves numeric null and missing values`() {
        assertEquals("{\"text\":\"quote\\\"\\n\\t\",\"missing\":null,\"nan\":null,\"value\":123}", uiAiPayload("text" to "quote\"\n\t", "missing" to null, "nan" to Double.NaN, "value" to 123))
        assertFailsWith<IllegalStateException> { uiAiPayload("model" to Any()) }
        assertFailsWith<IllegalArgumentException> { uiAiPayload("same" to 1, "same" to 2) }
        assertFailsWith<IllegalArgumentException> { uiAiPayload("rows" to List(101) { 1 }) }
    }

    @Test
    fun `reference IDs are deterministic snapshot-bound and do not expose raw identity`() {
        val first = uiAiEvidenceId("memory-selection", "private-snapshot", "object-1")
        assertEquals(first, uiAiEvidenceId("memory-selection", "private-snapshot", "object-1"))
        assertFalse(first == uiAiEvidenceId("memory-selection", "different-snapshot", "object-1"))
        assertFalse("private-snapshot" in first)
    }
}
