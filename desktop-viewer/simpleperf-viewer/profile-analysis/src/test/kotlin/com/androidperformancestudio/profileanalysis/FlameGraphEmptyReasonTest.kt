package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals

class FlameGraphEmptyReasonTest {
    @Test
    fun `first eliminating stage determines the exact empty reason`() {
        val cases =
            listOf(
                stages(sourceStackCount = 0, selectedThreadHasNoSamples = true) to
                    FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES,
                stages(sourceStackCount = 0, committedRangeExcludedSamples = true) to
                    FlameGraphEmptyReason.COMMITTED_RANGE_EMPTY,
                stages(afterPreviewCount = 0) to FlameGraphEmptyReason.PREVIEW_RANGE_EMPTY,
                stages(afterSearchCount = 0) to FlameGraphEmptyReason.SEARCH_FILTERED_ALL,
                stages(afterImplementationCount = 0) to FlameGraphEmptyReason.IMPLEMENTATION_FILTERED_ALL,
                stages(afterTransformCount = 0) to FlameGraphEmptyReason.TRANSFORMS_FILTERED_ALL,
                stages(incompleteStackCount = 1, projectedNodeCount = 0) to
                    FlameGraphEmptyReason.PROFILE_INCOMPLETE,
                stages(projectedNodeCount = 0, projectionFailure = "stable ID collision") to
                    FlameGraphEmptyReason.PROJECTION_FAILED,
            )

        cases.forEach { (counts, expected) ->
            assertEquals(expected, counts.emptyReason(), counts.toString())
        }
    }

    @Test
    fun `earlier empty stage wins over later diagnostics`() {
        val counts =
            stages(
                afterSearchCount = 0,
                afterImplementationCount = 0,
                afterTransformCount = 0,
                incompleteStackCount = 1,
                projectedNodeCount = 0,
                projectionFailure = "must remain diagnostic-only",
            )

        assertEquals(FlameGraphEmptyReason.SEARCH_FILTERED_ALL, counts.emptyReason())
    }
}

@Suppress("LongParameterList")
private fun stages(
    sourceStackCount: Int = 1,
    selectedThreadHasNoSamples: Boolean = false,
    committedRangeExcludedSamples: Boolean = false,
    afterPreviewCount: Int = 1,
    afterSearchCount: Int = 1,
    afterImplementationCount: Int = 1,
    afterTransformCount: Int = 1,
    incompleteStackCount: Int = 0,
    projectedNodeCount: Int = 1,
    projectionFailure: String? = null,
): FlameGraphStageCounts =
    FlameGraphStageCounts(
        sourceStackCount = sourceStackCount,
        selectedThreadHasNoSamples = selectedThreadHasNoSamples,
        committedRangeExcludedSamples = committedRangeExcludedSamples,
        afterPreviewCount = afterPreviewCount,
        afterSearchCount = afterSearchCount,
        afterImplementationCount = afterImplementationCount,
        afterTransformCount = afterTransformCount,
        incompleteStackCount = incompleteStackCount,
        projectedNodeCount = projectedNodeCount,
        projectionFailure = projectionFailure,
    )
