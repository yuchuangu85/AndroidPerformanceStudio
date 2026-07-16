package com.androidperformancestudio.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlameSearchDraftStateTest {
    @Test
    fun `older dispatch acknowledgment preserves a newer dirty draft`() {
        val state =
            FlameSearchDraftState
                .initial("")
                .edit("a")
                .markDispatched("a")
                .edit("ab")

        val acknowledged = state.acknowledge("a")

        assertEquals("a", acknowledged.authoritativeQuery)
        assertEquals("ab", acknowledged.draft)
        assertTrue(acknowledged.isDirty)
    }

    @Test
    fun `latest acknowledgment preserves typing that happened after dispatch`() {
        val state =
            FlameSearchDraftState
                .initial("a")
                .edit("ab")
                .markDispatched("ab")
                .edit("abc")

        val acknowledged = state.acknowledge("ab")

        assertEquals("abc", acknowledged.draft)
        assertTrue(acknowledged.isDirty)
    }

    @Test
    fun `genuine external query replaces local state`() {
        val state = FlameSearchDraftState.initial("a").edit("ab")

        val synchronized = state.acknowledge("external")

        assertEquals("external", synchronized.authoritativeQuery)
        assertEquals("external", synchronized.draft)
        assertFalse(synchronized.isDirty)
    }
}
