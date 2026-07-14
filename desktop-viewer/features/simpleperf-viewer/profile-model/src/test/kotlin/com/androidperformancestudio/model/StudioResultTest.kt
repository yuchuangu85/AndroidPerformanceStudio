package com.androidperformancestudio.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioResultTest {
    @Test
    fun `success keeps its value`() {
        val result: StudioResult<Int> = StudioResult.Success(42)

        assertEquals(42, assertIs<StudioResult.Success<Int>>(result).value)
    }

    @Test
    fun `failure keeps a structured error`() {
        val error =
            StudioError(
                category = ErrorCategory.PROCESS_EXIT,
                code = "ADB_EXIT_1",
                message = "adb exited with code 1",
            )

        val result: StudioResult<Nothing> = StudioResult.Failure(error)

        assertEquals(error, assertIs<StudioResult.Failure>(result).error)
    }
}
