@file:Suppress("MagicNumber")

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.analysis.StartupComparison
import com.androidperformancestudio.startup.analysis.StartupComparisonStatus
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.TitleBarTargetControls
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.rememberTitleBarTargetHost
import com.androidperformancestudio.ui.viewerColors
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StartupProfilerWorkspaceTest {
    @Test
    fun `startup uses a shared launchable package as the initial target on the shared device`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val state =
                mutableStateOf(
                    StartupProfilerState(
                        devices = listOf(StartupDevice("first", "First"), StartupDevice("second", "Second")),
                        selectedDeviceSerial = "first",
                        targets =
                            listOf(
                                StartupTarget("com.old", "com.old/.Main", true),
                                StartupTarget("com.shared", "com.shared/.Main", true),
                            ),
                    ),
                )
            setContent {
                ViewerTheme(darkTheme = true) {
                    val host = rememberTitleBarTargetHost()
                    LaunchedEffect(host) {
                        host.chooseDevice("second", "Second")
                        host.choosePackage("com.shared")
                    }
                    CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                        val actions =
                            StartupProfilerActions(
                                onSelectDevice = { serial -> state.value = state.value.copy(selectedDeviceSerial = serial) },
                                onSelectTarget = { component -> state.value = state.value.copy(selectedComponentName = component) },
                            )
                        Row { StartupTargetSelectors(state.value, actions, UiLanguage.ENGLISH) }
                        StartupScenarioControls(state.value, actions, UiLanguage.ENGLISH)
                    }
                }
            }

            waitForIdle()
            assertEquals("second", state.value.selectedDeviceSerial)
            assertEquals("com.shared/.Main", state.value.selectedComponentName)
            onNodeWithTag("startup-start-stop").assertIsEnabled()
        }

    @Test
    fun `startup can launch its selected activity while an unrelated system process is selected`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var started = false
            setContent {
                ViewerTheme(darkTheme = true) {
                    val host = rememberTitleBarTargetHost()
                    LaunchedEffect(host) {
                        host.chooseDevice("phone", "Phone")
                        host.choosePackage("com.android.systemui")
                    }
                    CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                        val state =
                            StartupProfilerState(
                                devices = listOf(StartupDevice("phone", "Phone")),
                                selectedDeviceSerial = "phone",
                                targets = listOf(StartupTarget("com.local", "com.local/.Main", true)),
                                selectedComponentName = "com.local/.Main",
                            )
                        Row { StartupTargetSelectors(state, StartupProfilerActions(), UiLanguage.ENGLISH) }
                        TitleBarTargetControls(host)
                        StartupScenarioControls(
                            state,
                            StartupProfilerActions(onStartStop = { started = true }),
                            UiLanguage.ENGLISH,
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithTag("startup-target").assertExists()
            onNodeWithTag("startup-start-stop").assertIsEnabled().performClick()
            assertTrue(started)
        }

    @Test
    fun `device target stays in the title bar and the application target renders inline`() {
        val controls =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupWorkspaceControls.kt"),
            )
        val selectors =
            controls
                .substringAfter("public fun StartupTargetSelectors(")
                .substringBefore("internal fun StartupScenarioControls(")
        assertTrue(selectors.contains("TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE)"))
        assertTrue(!selectors.contains("TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS)"))
        assertTrue(selectors.contains("DeviceDropdownSelector("))
        assertTrue(selectors.contains("DropdownSelector("))
        assertTrue(!selectors.contains("targetHost?.choosePackage("))
        assertTrue(selectors.contains("actions.onSelectTarget(it.componentName)"))
        assertTrue(selectors.contains("Res.string.application"))
        assertTrue(selectors.contains("if (!inTitleBar) HeaderSpacer()"))
    }

    @Test
    fun `Startup evidence panel uses the shared palette without a second surface tint`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(StartupProfilerState(), StartupProfilerActions(), UiLanguage.ENGLISH)
                }
            }

            val panel = onNodeWithTag("startup-profile-status").captureToImage().toPixelMap()
            assertEquals(viewerColors(true).panel, panel[3, panel.height / 2])
        }

    @Test
    fun `empty workspace keeps scenario controls and type tabs available without a top banner`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var requested: StartupType? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        StartupProfilerState(),
                        StartupProfilerActions(onSelectStartupType = { requested = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-scenario").assertIsDisplayed()
            onNodeWithTag("startup-type-WARM").performClick()
            assertEquals(StartupType.WARM, requested)
            onNodeWithTag("startup-start-stop").assertIsNotEnabled()
            onNodeWithTag("startup-date").assertDoesNotExist()
        }

    @Test
    fun `target selectors put the device before the application`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    Row {
                        StartupTargetSelectors(
                            startupVisualState(),
                            StartupProfilerActions(),
                            UiLanguage.ENGLISH,
                        )
                    }
                }
            }

            val device = onNodeWithTag("startup-device").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val target = onNodeWithTag("startup-target").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(device.right <= target.left)
        }

    @Test
    fun `running workspace locks configuration but keeps cancellation available`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var cancelled = false
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        startupVisualState().copy(isRunning = true, completedRuns = 2, totalRuns = 5),
                        StartupProfilerActions(onStartStop = { cancelled = true }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-type-HOT").assertIsNotEnabled()
            onNodeWithTag("startup-start-stop").performScrollTo().assertIsEnabled().performClick()
            assertTrue(cancelled)
            onNodeWithTag("startup-main").performMouseInput { scroll(1200f) }
            waitForIdle()
            onNodeWithTag("startup-progress").assertIsDisplayed()
            onNodeWithText("Experiment progress (including compilation warmups): 2 / 5").assertIsDisplayed()
        }

    @Test
    fun `operation messages and errors appear below the baseline comparison`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        startupVisualState().copy(
                            operationMessage = "Capture finished",
                            errorMessage = "Trace inspection failed",
                        ),
                        StartupProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("startup-scenario").assertIsDisplayed()
            onNodeWithTag("startup-metric-TTID").assertIsDisplayed()
            onNodeWithText("Capture finished").assertIsNotDisplayed()
            onNodeWithText("Trace inspection failed").assertIsNotDisplayed()
            onNodeWithText("Trace inspection failed").performScrollTo()
            onNodeWithText("Capture finished").assertIsDisplayed()
            onNodeWithText("Trace inspection failed").assertIsDisplayed()

            val comparison = onNodeWithText("Baseline Comparison").fetchSemanticsNode().boundsInRoot
            val message = onNodeWithText("Capture finished").fetchSemanticsNode().boundsInRoot
            assertTrue(message.top >= comparison.bottom)
        }

    @Test
    fun `stacked layout retains messages errors and running progress after the analysis content`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(
                        startupVisualState().copy(
                            isRunning = true,
                            completedRuns = 2,
                            totalRuns = 5,
                            operationMessage = "Measuring startup",
                            errorMessage = "Optional trace unavailable",
                        ),
                        StartupProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithText("Experiment progress (including compilation warmups): 2 / 5").performScrollTo()
            onNodeWithTag("startup-stacked-workspace").performMouseInput { scroll(40f) }
            waitForIdle()
            onNodeWithText("Measuring startup").assertIsDisplayed()
            onNodeWithText("Optional trace unavailable").assertIsDisplayed()
            onNodeWithText("Experiment progress (including compilation warmups): 2 / 5").assertIsDisplayed()
            onNodeWithTag("startup-progress").assertIsDisplayed()

            val comparison = onNodeWithText("Baseline Comparison").fetchSemanticsNode().boundsInRoot
            val message = onNodeWithText("Measuring startup").fetchSemanticsNode().boundsInRoot
            assertTrue(message.top >= comparison.bottom)
        }

    @Test
    fun `reference layout has four real KPIs multiple run rows and a separate evidence rail`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(startupVisualState(), StartupProfilerActions(), UiLanguage.SIMPLIFIED_CHINESE)
                }
            }
            listOf("TTID", "TTFD", "FIRST_FRAME", "P95").forEach {
                onNodeWithTag("startup-metric-$it").assertIsDisplayed()
            }
            onNodeWithTag("startup-waterfall-run-1").assertIsDisplayed()
            onNodeWithTag("startup-waterfall-run-5").assertIsDisplayed()
            val main = onNodeWithTag("startup-main").fetchSemanticsNode().boundsInRoot
            val rail = onNodeWithTag("startup-evidence-rail").fetchSemanticsNode().boundsInRoot
            assertEquals(4f, main.left)
            assertEquals(4f, main.top)
            assertEquals(1332f, rail.right)
            assertEquals(4f, rail.left - main.right)
            val metrics =
                listOf("TTID", "TTFD", "FIRST_FRAME", "P95").map {
                    onNodeWithTag("startup-metric-$it").fetchSemanticsNode().boundsInRoot
                }
            assertEquals(4f, metrics.first().left - main.left)
            assertEquals(4f, main.right - metrics.last().right)
            metrics.zipWithNext().forEach { (left, right) -> assertEquals(4f, right.left - left.right) }
            assertTrue(rail.width >= 280f)
            onNodeWithTag("startup-date").assertDoesNotExist()
        }

    @Test
    fun `table and waterfall selections and trace failure feedback use real callbacks`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            var selected: String? = null
            var trace: String? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        startupVisualState(),
                        StartupProfilerActions(onSelectRun = { selected = it }, onOpenTrace = {
                            trace = it
                            false
                        }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-waterfall-run-3").performClick()
            assertEquals("run-3", selected)
            onNodeWithTag("startup-main").performScrollToNode(hasTestTag("startup-table-run-4"))
            waitForIdle()
            onNodeWithTag("startup-main").performMouseInput { scroll(500f) }
            waitForIdle()
            onNodeWithTag("startup-table-run-4").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals("run-4", selected)
            onNodeWithTag("startup-evidence-rail").performScrollToNode(hasTestTag("startup-open-trace"))
            onNodeWithTag("startup-open-trace").performClick()
            assertEquals("/synthetic/startup.perfetto-trace", trace)
            onNodeWithText("Trace could not be opened from this computer.").assertIsDisplayed()
        }

    @Test
    fun `advanced controls preserve timeout trace and compilation warmups`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            var tracing: Boolean? = null
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(
                        startupVisualState(),
                        StartupProfilerActions(onSetPerfettoTraceEnabled = { tracing = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-timeout").performScrollTo().assertIsDisplayed()
            onNodeWithTag("startup-perfetto").performScrollTo().performClick()
            assertEquals(true, tracing)
        }

    @Test
    fun `waterfall phase filtering retains evidence intervals selection and real export action`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            var exports = 0
            var selected: String? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        startupVisualState(),
                        StartupProfilerActions(onSelectRun = { selected = it }, onExportCsv = { exports++ }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-waterfall-phase-filter").performClick()
            onAllNodesWithText("Activity Create").onLast().performClick()
            onNodeWithTag("startup-waterfall-run-2")
                .assertContentDescriptionContains(
                    "AGENT · Activity Create 108.0 ms Exact",
                ).performClick()
            assertEquals("run-2", selected)
            onNodeWithTag("startup-waterfall-export").performClick()
            assertEquals(1, exports)
            onNodeWithTag("startup-waterfall-phase-filter").performClick()
            onNodeWithText("All phases").performClick()
            onNodeWithTag("startup-waterfall-run-2").assertContentDescriptionContains(
                "Process Bootstrap",
                substring = true,
            )
        }

    @Test
    fun `running experiment locks provenance and comparison import and export`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(
                        startupVisualState().copy(isRunning = true, completedRuns = 2, totalRuns = 7),
                        StartupProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-waterfall-export").assertIsNotEnabled()
            onNode(
                hasAnyAncestor(hasTestTag("startup-profile-artifact")) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled),
            ).performScrollTo().assertIsNotEnabled()
            onNodeWithTag("startup-main").performMouseInput {
                moveTo(center)
                scroll(2000f)
            }
            waitForIdle()
            onNodeWithTag("startup-import-comparison").assertIsNotEnabled()
            onNodeWithText("Experiment progress (including compilation warmups): 2 / 7").assertIsDisplayed()
        }

    @Test
    fun `phase filter resets when its only evidence leaves the run preview`() =
        runDesktopComposeUiTest(width = 1100, height = 600) {
            val original = startupVisualState().analysis!!.runs.first()
            val runs =
                StartupAnalyzer()
                    .analyze(
                        (1..6).map { index ->
                            original.copy(
                                id = "preview-$index",
                                iteration = index,
                                milestones =
                                    if (index == 5) {
                                        original.milestones
                                    } else {
                                        original.milestones.filter {
                                            it.kind in
                                                listOf(
                                                    StartupMilestoneKind.PROCESS_START,
                                                    StartupMilestoneKind.INITIALIZER_ENTER,
                                                )
                                        }
                                    },
                            )
                        },
                    ).runs
            val selected = mutableStateOf(runs[4])
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupRunsWaterfall(runs, selected.value, StartupProfilerActions(), UiLanguage.ENGLISH)
                }
            }
            onNodeWithTag("startup-waterfall-phase-filter").performClick()
            onAllNodesWithText("Activity Create").onLast().performClick()
            onNodeWithTag("startup-waterfall-preview-1").assertContentDescriptionContains("AGENT · ")
            runOnIdle { selected.value = runs[5] }
            onNodeWithText("All phases").assertIsDisplayed()
            onNodeWithTag("startup-waterfall-preview-6").assertContentDescriptionContains(
                "Process Bootstrap",
                substring = true,
            )
        }

    @Test
    fun `narrow layout stacks evidence without removing content`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(startupVisualState(), StartupProfilerActions(), UiLanguage.ENGLISH)
                }
            }
            onNodeWithTag("startup-stacked-workspace").performScrollToNode(hasTestTag("startup-profile-status"))
            onNodeWithTag("startup-profile-status").assertIsDisplayed()
        }

    @Test
    fun `baseline profile is not inferred from generic speed profile compilation`() {
        val evidence =
            startupVisualState()
                .analysis!!
                .runs
                .first()
                .compilationEvidence!!
        assertTrue(evidence.hasVerifiedBaselineProfile())
        val genericProfile = evidence.copy(profileSource = StartupProfileSource.MACROBENCHMARK)
        assertEquals(false, genericProfile.hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(baselineProfileArtifact = null).hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(verified = false).hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(failureReason = "preparation failed").hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(requestedMode = CompilationMode.CURRENT).hasVerifiedBaselineProfile())
    }

    @Test
    fun `profile provenance editor uses the real callback`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            var artifact: String? = null
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(
                        startupVisualState(),
                        StartupProfilerActions(onUpdateBaselineProfileArtifact = { artifact = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("startup-profile-artifact")))
                .performScrollTo()
                .performTextReplacement("sha256:build-supplied")
            assertEquals("sha256:build-supplied", artifact)
        }

    @Test
    fun `leaving speed profile clears mode-only provenance without throwing`() {
        val configured =
            startupVisualState().copy(
                config = startupVisualState().config.copy(warmupRuns = 2, baselineProfileArtifact = "build-id"),
            )
        val current = configured.withCompilationMode(CompilationMode.CURRENT)
        assertEquals(null, current.config.baselineProfileArtifact)
        assertEquals(StartupProfileSource.UNVERIFIED, current.config.profileSource)
        assertEquals(0, current.config.warmupRuns)
        assertEquals(
            "build-id",
            configured.withCompilationMode(CompilationMode.SPEED_PROFILE).config.baselineProfileArtifact,
        )
    }

    @Test
    fun `comparison shows both cohorts evidence confidence threshold and real import action`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            val original = startupVisualState()
            var imports = 0
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        original.copy(
                            baseline = original.analysis,
                            comparison =
                                StartupComparison(
                                    StartupComparisonStatus.NO_CHANGE,
                                    medianDifferenceMs = 0.0,
                                    percentDifference = 0.0,
                                    confidenceIntervalLowMs = -10.0,
                                    confidenceIntervalHighMs = 10.0,
                                    practicalThresholdMs = 24.0,
                                ),
                        ),
                        StartupProfilerActions(onImport = { imports++ }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-import-comparison").performScrollTo().performClick()
            assertEquals(1, imports)
            onNodeWithTag("startup-main").performMouseInput { scroll(1800f) }
            waitForIdle()
            onNodeWithTag("startup-comparison-previous").assertIsDisplayed()
            onNodeWithText("Previous experiment · TTID source: EVENT_LOG · Evidence: Exact · n=5 · missing=0")
                .assertIsDisplayed()
            onNodeWithText("TTID median change: +0.0% · practical threshold ±24.0 ms")
                .performScrollTo()
                .assertIsDisplayed()
        }

    @Test
    fun `comparison evidence retains mixed confidence and unavailable sources`() {
        val original = startupVisualState().analysis!!
        val run = original.runs.first()
        val mixed =
            StartupAnalyzer().analyze(
                listOf(
                    run,
                    run.copy(
                        id = "missing",
                        ttidEvidence =
                            run.ttidEvidence.copy(
                                source = null,
                                confidence = EvidenceConfidence.UNAVAILABLE,
                            ),
                        platform = run.platform.copy(displayedTimeMs = null),
                    ),
                ),
            )
        assertEquals(
            "Previous · TTID source: EVENT_LOG / Unavailable · Evidence: Exact / Unavailable · n=1 · missing=1",
            comparisonEvidenceText("Previous", mixed, UiLanguage.ENGLISH),
        )
    }

    @Test
    fun `cohort metrics keep missing and mixed confidence and selected long-run preview`() {
        val state = startupVisualState()
        val base = state.analysis!!.runs.first()
        val analysis =
            StartupAnalyzer().analyze(
                listOf(
                    base,
                    base.copy(
                        id = "missing",
                        ttfdEvidence = base.ttfdEvidence.copy(confidence = EvidenceConfidence.UNAVAILABLE),
                        platform = base.platform.copy(fullyDrawnTimeMs = null),
                    ),
                ),
            )
        val caption = metricEvidenceText(analysis, analysis.fullyDrawn, { it.ttfdEvidence }, UiLanguage.ENGLISH)
        assertEquals("Evidence: Exact / Unavailable · n=1 · missing=1", caption)
        val runs = (1..100).map { base.copy(id = "long-$it", iteration = it) }
        val preview = startupWaterfallPreview(runs, runs.last())
        assertEquals(5, preview.size)
        assertEquals("long-100", preview.last().id)
    }

    @Test
    fun `unavailable phases keep real total values and selectable run rows`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            val original = startupVisualState()
            val analysis =
                StartupAnalyzer().analyze(
                    original.analysis!!.runs.map { it.copy(milestones = emptyList(), phases = emptyList()) },
                )
            var selected: String? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        original.copy(analysis = analysis),
                        StartupProfilerActions(onSelectRun = { selected = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-waterfall-run-5").performClick()
            assertEquals("run-5", selected)
        }

    @Test
    fun `fully missing metrics report zero available samples`() {
        val run = startupVisualState().analysis!!.runs.first()
        val analysis =
            StartupAnalyzer().analyze(
                listOf(
                    run.copy(
                        platform = run.platform.copy(fullyDrawnTimeMs = null),
                        ttfdEvidence = run.ttfdEvidence.copy(confidence = EvidenceConfidence.UNAVAILABLE),
                    ),
                ),
            )
        assertEquals(
            "Evidence: Unavailable · n=0 · missing=1",
            metricEvidenceText(analysis, analysis.fullyDrawn, {
                it.ttfdEvidence
            }, UiLanguage.ENGLISH),
        )
    }
}
