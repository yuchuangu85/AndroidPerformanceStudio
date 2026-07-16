package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.FlameGraphEmptyReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FlameGraphEmptyStateTest {
    @Test
    fun `every empty reason has one truthful message and concrete recovery`() {
        val expected =
            mapOf(
                FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES to
                    ("The selected thread has no samples." to FlameGraphRecoveryAction.SELECT_ALL_THREADS),
                FlameGraphEmptyReason.COMMITTED_RANGE_EMPTY to
                    ("The selected time range contains no samples." to FlameGraphRecoveryAction.RESET_TIME_RANGE),
                FlameGraphEmptyReason.PREVIEW_RANGE_EMPTY to
                    ("The preview range contains no samples." to FlameGraphRecoveryAction.CANCEL_PREVIEW),
                FlameGraphEmptyReason.SEARCH_FILTERED_ALL to
                    ("Search removed all samples." to FlameGraphRecoveryAction.CLEAR_SEARCH),
                FlameGraphEmptyReason.IMPLEMENTATION_FILTERED_ALL to
                    (
                        "The implementation filter removed all samples." to
                            FlameGraphRecoveryAction.SHOW_ALL_IMPLEMENTATIONS
                    ),
                FlameGraphEmptyReason.TRANSFORMS_FILTERED_ALL to
                    ("Stack transforms removed all samples." to FlameGraphRecoveryAction.UNDO_TRANSFORM),
                FlameGraphEmptyReason.PROFILE_INCOMPLETE to
                    (
                        "The profile does not contain complete call stacks." to
                            FlameGraphRecoveryAction.REVIEW_DATA_QUALITY
                    ),
                FlameGraphEmptyReason.PROJECTION_FAILED to
                    ("The flame graph could not be projected." to FlameGraphRecoveryAction.RETRY_PROJECTION),
            )

        expected.forEach { (reason, expectation) ->
            val content = flameGraphEmptyStateContent(reason, diagnosticDetails = "PROCESS_EXIT_1: internal detail")
            assertEquals(expectation.first, content.message)
            assertEquals(expectation.second, content.recoveryAction)
            assertFalse(content.message.contains("PROCESS_EXIT_1"))
            assertEquals("PROCESS_EXIT_1: internal detail", content.diagnosticDetails)
        }
    }
}
