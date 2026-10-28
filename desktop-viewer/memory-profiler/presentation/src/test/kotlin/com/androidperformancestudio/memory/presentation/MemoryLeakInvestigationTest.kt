@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryLiveEvent
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryLiveStatus
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.LeakCanaryTraceElement
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val MEMORY_LEAK_SCOPE_NOTE =
    "Bounded lifecycle / ObjectWatcher analysis; " +
        "Android framework/OEM leak matching is not included by default."

@OptIn(ExperimentalTestApi::class)
class MemoryLeakInvestigationTest {
    @Test
    fun `offline heap enables explicit analysis without a selected device or process`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var analyzed = 0
            var started = 0
            setContent {
                MemoryProfilerScreen(
                    MemoryProfilerState(
                        viewMode = MemoryProfilerViewMode.LeakCanary,
                        snapshotSummary = HeapSnapshotSummary("offline"),
                    ),
                    MemoryProfilerActions(onAnalyzeLeaks = { analyzed++ }, onStartLiveLeakMonitoring = { started++ }),
                )
            }
            assertEquals(0, analyzed)
            assertEquals(0, started)
            onNodeWithTag("memory-leak-analyze").assertIsEnabled().performClick()
            onNodeWithTag("memory-leak-live-start").assertIsNotEnabled()
            onNodeWithTag("memory-leak-live-stop").assertIsNotEnabled()
            assertEquals(1, analyzed)
            assertEquals(0, started)
        }

    @Test
    fun `analysis loading failure retry and empty reports remain distinct`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var state by mutableStateOf(MemoryProfilerState(viewMode = MemoryProfilerViewMode.LeakCanary))
            var analyzed = 0
            setContent { MemoryProfilerScreen(state, MemoryProfilerActions(onAnalyzeLeaks = { analyzed++ })) }
            onNodeWithTag("memory-leak-analyze").assertIsNotEnabled()
            onNodeWithText("Load an HPROF snapshot to analyze memory leaks.").assertExists()
            runOnIdle { state = state.copy(snapshotSummary = HeapSnapshotSummary("heap")) }
            onNodeWithText("Leak analysis was not run for this source.").assertExists()
            onNodeWithTag("memory-leak-analyze").assertIsEnabled()
            runOnIdle { state = state.copy(leakCanaryReport = LeakCanaryReport(status = LeakCanaryStatus.ANALYZING)) }
            onNodeWithTag("memory-profiler-leak-canary-loading").assertExists()
            onNodeWithTag("memory-leak-analyze").assertIsNotEnabled()
            runOnIdle {
                state =
                    state.copy(
                        leakCanaryReport = LeakCanaryReport(status = LeakCanaryStatus.FAILED, message = "Broken HPROF"),
                    )
            }
            onNodeWithText("Leak analysis failed: Broken HPROF").assertExists()
            onNodeWithText("Retry").performClick()
            assertEquals(1, analyzed)
            runOnIdle {
                state = state.copy(leakCanaryReport = LeakCanaryReport(status = LeakCanaryStatus.NO_CANDIDATES))
            }
            onNodeWithText("No leak candidates were identified for Shark analysis.").assertExists()
            onNodeWithTag("memory-leak-analyze").assertIsEnabled()
            runOnIdle { state = state.copy(leakCanaryReport = LeakCanaryReport(status = LeakCanaryStatus.COMPLETED)) }
            onNodeWithText("No Shark leak traces found.").assertExists()
            onNodeWithTag("memory-leak-path").assertDoesNotExist()
        }

    @Test
    fun `completed report shows its scope without crowding a short window`() =
        runDesktopComposeUiTest(width = 620, height = 480) {
            var state by mutableStateOf(memoryLeakFixture())
            setContent { MemoryProfilerScreen(state, MemoryProfilerActions()) }
            onNodeWithTag("memory-leak-analysis-note").assertTextEquals(MEMORY_LEAK_SCOPE_NOTE).assertIsDisplayed()
            val workspace = onNodeWithTag("memory-leak-workspace").fetchSemanticsNode().boundsInRoot
            assertTrue(workspace.height > 100f)
            runOnIdle { state = state.copy(leakCanaryReport = state.leakCanaryReport.copy(message = " \n ")) }
            onNodeWithTag("memory-leak-analysis-note").assertDoesNotExist()
            runOnIdle {
                state =
                    state.copy(
                        leakCanaryReport =
                            state.leakCanaryReport.copy(
                                status = LeakCanaryStatus.NOT_RUN,
                                message = MEMORY_LEAK_SCOPE_NOTE,
                            ),
                    )
            }
            onNodeWithTag("memory-leak-analysis-note").assertDoesNotExist()
        }

    @Test
    fun `findings navigate by identity while inspector contents only expand inline`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val selected = mutableListOf<Long>()
            val expanded = mutableListOf<Pair<Long, Int>>()
            val classLookups = mutableListOf<String>()
            val copied = mutableListOf<Long>()
            val pinned = mutableListOf<Long>()
            var back = 0
            var forward = 0
            setContent {
                MemoryProfilerScreen(
                    memoryLeakFixture().copy(instanceHistory = listOf(11L, 0x303L, 22L), instanceHistoryIndex = 1),
                    MemoryProfilerActions(
                        onSelectInstance = { selected += it },
                        onLoadInspectorObject = { id, start -> expanded += id to start },
                        onSelectClass = { classLookups += it },
                        onCopyObjectId = { copied += it },
                        onTogglePinnedInstance = { pinned += it },
                        onNavigateInstanceBack = { back++ },
                        onNavigateInstanceForward = { forward++ },
                    ),
                )
            }
            assertEquals(emptyList(), selected)
            onNodeWithTag("memory-leak-finding-0").performClick()
            onNodeWithTag("memory-leak-finding-1").performClick()
            onNodeWithTag("memory-leak-retained-object").performClick()
            onNodeWithTag("memory-leak-path-object-0").performScrollTo().performClick()
            onNodeWithTag("memory-leak-path-object-1").performScrollTo().performClick()
            assertEquals(listOf(0x303L, 0x404L, 0x404L, 0x101L, 0x202L), selected)
            assertEquals(emptyList(), classLookups)
            onNodeWithTag("memory-leak-suspect-edge-1").assertExists()
            onNodeWithText("mDestroyed = true").assertExists()
            onNodeWithTag("memory-inspector-row-0").performClick()
            assertEquals(0x202L, selected.last())
            assertEquals(listOf(0x505L to 0), expanded)
            onNodeWithContentDescription("Copy object ID").assertDoesNotExist()
            onNodeWithText("Pin object").assertDoesNotExist()
            onNodeWithContentDescription("Back").assertDoesNotExist()
            onNodeWithContentDescription("Forward").assertDoesNotExist()
            assertTrue(copied.isEmpty())
            assertTrue(pinned.isEmpty())
            assertEquals(0, back)
            assertEquals(0, forward)
        }

    @Test
    fun `missing identities never fall back to class navigation`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val selected = mutableListOf<Long>()
            val classLookups = mutableListOf<String>()
            val leak =
                memoryLeakFixture().leakCanaryReport.applicationLeaks.first().copy(
                    leakingObjectId = null,
                    trace = listOf(LeakCanaryTraceElement("sample.HomeActivity")),
                )
            setContent {
                MemoryProfilerScreen(
                    memoryLeakFixture().copy(
                        leakCanaryReport =
                            LeakCanaryReport(
                                status = LeakCanaryStatus.COMPLETED,
                                applicationLeaks = listOf(leak),
                            ),
                    ),
                    MemoryProfilerActions(
                        onSelectInstance = { selected += it },
                        onSelectClass = { classLookups += it },
                    ),
                )
            }
            onNodeWithTag("memory-leak-finding-0").performClick()
            onNodeWithTag("memory-leak-retained-object").assertDoesNotExist()
            onNodeWithTag("memory-leak-path-object-0").performScrollTo().assertIsNotEnabled()
            assertEquals(emptyList(), selected)
            assertEquals(emptyList(), classLookups)
        }

    @Test
    fun `finding search scope filtering and snapshot changes do not navigate implicitly`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            var state by mutableStateOf(memoryLeakFixture())
            val selected = mutableListOf<Long>()
            setContent { MemoryProfilerScreen(state, MemoryProfilerActions(onSelectInstance = { selected += it })) }
            onNodeWithTag("memory-leak-search").performTextReplacement("0x404")
            onNodeWithTag("memory-leak-finding-0").assertDoesNotExist()
            onNodeWithTag("memory-leak-finding-1").assertExists()
            onNodeWithTag("memory-leak-search").performTextReplacement("no-such-object")
            onNodeWithTag("memory-leak-path").assertDoesNotExist()
            onNodeWithTag("memory-leak-search").performTextReplacement("")
            onNodeWithTag("memory-leak-scope").performClick()
            onNode(
                hasText("Library leaks") and hasAnyAncestor(hasTestTag("memory-leak-scope-menu")),
                useUnmergedTree = true,
            ).performClick()
            onNodeWithTag("memory-leak-finding-0").assertDoesNotExist()
            onNodeWithTag("memory-leak-finding-2").assertExists()
            runOnIdle {
                state = state.copy(activeSnapshotId = "second", snapshotSummary = HeapSnapshotSummary("second"))
            }
            onNodeWithTag("memory-leak-finding-0").assertExists()
            assertEquals(emptyList(), selected)
        }

    @Test
    fun `live controls are independent explicit and never reinterpret events as heap objects`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var state by mutableStateOf(
                memoryLeakFixture().copy(
                    selectedDeviceSerial = "device",
                    selectedProcessId = 42,
                    isDumping = true,
                ),
            )
            var started = 0
            var stopped = 0
            val selected = mutableListOf<Long>()
            setContent {
                MemoryProfilerScreen(
                    state,
                    MemoryProfilerActions(
                        onStartLiveLeakMonitoring = { started++ },
                        onStopLiveLeakMonitoring = { stopped++ },
                        onSelectInstance = { selected += it },
                    ),
                )
            }
            assertEquals(0, started)
            onNodeWithTag("memory-leak-live-start").assertIsEnabled().performClick()
            assertEquals(1, started)
            val event = LeakCanaryLiveEvent(1, "retained", 123, "live.RetainedActivity", retained = true)
            runOnIdle {
                state =
                    state.copy(
                        isDumping = false,
                        leakCanaryLiveSession =
                            LeakCanaryLiveSession(
                                status = LeakCanaryLiveStatus.RUNNING,
                                events = listOf(event),
                            ),
                    )
            }
            onNodeWithTag("memory-leak-analyze").assertIsEnabled()
            onNodeWithTag("memory-leak-live-start").assertIsNotEnabled()
            onNodeWithTag("memory-leak-live-stop").assertIsEnabled().performClick()
            onNodeWithTag("memory-leak-live-evidence").assertExists()
            onNodeWithText("live.RetainedActivity").assertHasNoClickAction()
            assertEquals(1, stopped)
            assertEquals(emptyList(), selected)
            runOnIdle {
                state =
                    state.copy(
                        leakCanaryLiveSession =
                            state.leakCanaryLiveSession.copy(
                                status = LeakCanaryLiveStatus.STOPPING,
                            ),
                    )
            }
            onNodeWithTag("memory-leak-live-start").assertIsNotEnabled()
            onNodeWithTag("memory-leak-live-stop").assertIsNotEnabled()
        }
}

internal fun memoryLeakFixture(): MemoryProfilerState {
    val trace = memoryLeakTrace()
    val leak =
        LeakCanaryLeak(
            signature = "retained-home",
            shortDescription = "Destroyed activity retained by SessionStore.activity",
            leakingClassName = "sample.HomeActivity",
            retainedHeapByteSize = 196608,
            retainedObjectCount = 42,
            gcRootType = "System class",
            trace = trace,
            leakingObjectId = 0x303L,
        )
    return MemoryProfilerState(
        viewMode = MemoryProfilerViewMode.LeakCanary,
        activeSnapshotId = "offline",
        snapshotSummary = HeapSnapshotSummary("offline", sourceFile = Path.of("after-rotation.hprof")),
        leakCanaryReport =
            LeakCanaryReport(
                status = LeakCanaryStatus.COMPLETED,
                analyzedClassCount = 6484,
                analyzedObjectCount = 412587,
                candidateObjectCount = 3,
                analysisDurationMillis = 1249,
                message = MEMORY_LEAK_SCOPE_NOTE,
                applicationLeaks =
                    listOf(
                        leak,
                        leak.copy(
                            signature = "second-home",
                            leakingObjectId = 0x404L,
                            trace =
                                trace.dropLast(1) + trace.last().copy(objectId = 0x404L),
                        ),
                    ),
                libraryLeaks =
                    listOf(
                        leak.copy(
                            signature = "library",
                            leakingClassName = "library.CachedContext",
                            leakingObjectId = 0x606L,
                        ),
                    ),
            ),
        selectedInstanceDetail =
            MemoryInstanceDetail(
                objectId = 0x303L,
                className = "sample.HomeActivity",
                shallowSize = 192,
                retainedSize = 196608,
                depth = 3,
                isArray = false,
                elementCount = null,
                fields = listOf(MemoryInstanceField("owner", "SessionStore@0x505", 0x505L, "sample.SessionStore")),
                referenceChain = emptyList(),
            ),
    )
}

private fun memoryLeakTrace(): List<LeakCanaryTraceElement> =
    listOf(
        LeakCanaryTraceElement("sample.App", "store", "STATIC_FIELD", "NOT_LEAKING", objectId = 0x101L),
        LeakCanaryTraceElement(
            "sample.SessionStore",
            "activity",
            "INSTANCE_FIELD",
            "UNKNOWN",
            objectId = 0x202L,
            isSuspect = true,
        ),
        LeakCanaryTraceElement(
            "sample.HomeActivity",
            leakingStatus = "LEAKING",
            objectId = 0x303L,
            labels = listOf("mDestroyed = true"),
        ),
    )
