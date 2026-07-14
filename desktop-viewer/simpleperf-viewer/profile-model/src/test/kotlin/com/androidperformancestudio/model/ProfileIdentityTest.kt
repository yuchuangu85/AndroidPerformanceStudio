package com.androidperformancestudio.model

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ProfileIdentityTest {
    @Test
    fun `time point requires a non-negative error bound`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileTimePoint(ProfileClockDomain("simpleperf"), 10, -1)
        }
    }

    @Test
    fun `thread identity includes its source and process`() {
        val source = ProfileSourceId("simpleperf")

        assertNotEquals(
            ProfileThreadKey(source, ProfileProcessKey(source, 10), 20),
            ProfileThreadKey(source, ProfileProcessKey(source, 11), 20),
        )
    }
}
