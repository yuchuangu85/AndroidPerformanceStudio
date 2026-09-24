package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MemoryObjectEvidenceTest {
    @Test
    fun `object inspector distinguishes unavailable values from measured zero`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent {
                MemoryProfilerScreen(
                    state =
                        MemoryProfilerState(
                            viewMode = MemoryProfilerViewMode.ClassList,
                            selectedClassName = "example.Bitmap",
                            selectedInstanceDetail =
                                MemoryInstanceDetail(
                                    objectId = 42,
                                    className = "example.Bitmap",
                                    shallowSize = 0,
                                    shallowSizeKnown = false,
                                    retainedSize = null,
                                    nativeSize = 4096,
                                    depth = null,
                                    isArray = false,
                                    elementCount = null,
                                    fields = emptyList(),
                                    referenceChain = emptyList(),
                                ),
                        ),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithText("Reachability: not established by available GC Roots").assertExists()
            onNodeWithText("Shallow Unavailable · Retained Unavailable · Estimated native size 4.0 KB").assertExists()
            onNodeWithText("No fields in this parsed object").assertExists()
        }
}
