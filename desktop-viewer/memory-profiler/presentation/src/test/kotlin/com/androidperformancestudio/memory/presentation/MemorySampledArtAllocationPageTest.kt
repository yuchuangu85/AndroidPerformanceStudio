package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MemorySampledArtAllocationPageTest {
    @Test
    fun `sampled analysis distinguishes verified empty unavailable and counted results`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val trace =
                MemorySampledArtTrace(
                    file = "/tmp/art-sampled.pb",
                    fileSizeBytes = 9999,
                    packageName = "com.example",
                    pid = 42,
                    sdkLevel = 36,
                    samplingIntervalBytes = 4096,
                    durationMs = 10_000,
                    source = "perfetto/android.heapprofd/com.android.art",
                )
            var analysis by mutableStateOf<MemorySampledArtAnalysis>(
                MemorySampledArtAnalysis.Analyzed(0, 0, 0, emptyList()),
            )
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemorySampledArtAllocationPage(trace, analysis, false, UiLanguage.ENGLISH)
                }
            }
            onNodeWithTag("memory-art-sampled-empty").assertExists()
            onNodeWithTag("memory-art-sampled-summary").assertDoesNotExist()
            runOnIdle { analysis = MemorySampledArtAnalysis.Unavailable("TRACE_PROCESSOR_NOT_FOUND") }
            onNodeWithTag("memory-art-sampled-analysis-unavailable").assertExists()
            runOnIdle {
                analysis =
                    MemorySampledArtAnalysis.Analyzed(
                        sampleRows = 2,
                        sampledObjectCount = 3,
                        sampledBytes = 128,
                        callSites =
                            listOf(MemorySampledArtCallSite(11, 3, 128, listOf("com.example.Work.allocate"))),
                    )
            }
            onNodeWithTag("memory-art-sampled-summary")
                .assertTextEquals("Trace Processor verified 2 sample rows · 3 sampled objects · 128 sampled B")
            onNodeWithTag("memory-art-sampled-empty").assertDoesNotExist()
            runOnIdle {
                analysis = MemorySampledArtAnalysis.Failed("TRACE_QUERY_FAILED")
            }
            onNodeWithTag("memory-art-sampled-analysis-failed").assertExists()
        }

    @Test
    fun `local ART import is labelled without invented PID or API`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val trace =
                MemorySampledArtTrace(
                    file = "/tmp/imported.pb",
                    fileSizeBytes = 123,
                    source = "local Perfetto import; ART samples queried via Trace Processor",
                    origin = MemorySampledArtOrigin.IMPORT,
                )
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemorySampledArtAllocationPage(
                        trace,
                        MemorySampledArtAnalysis.Analyzed(0, 0, 0, emptyList()),
                        false,
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithText("Local Perfetto import · trace 123 B · capture process, API, interval and duration unknown")
                .assertExists()
        }
}
