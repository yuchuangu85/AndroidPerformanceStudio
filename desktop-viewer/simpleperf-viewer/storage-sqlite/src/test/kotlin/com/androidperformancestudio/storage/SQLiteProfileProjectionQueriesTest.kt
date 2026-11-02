@file:Suppress("LargeClass")

package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallNodePath
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphEmptyReason
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartEmptyReason
import com.androidperformancestudio.profileanalysis.StackChartProjector
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CancellationException
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SQLiteProfileProjectionQueriesTest {
    @Test
    fun `committed range never stretches the selected thread terminal sample`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()
            store.execute("INSERT INTO thread(thread_id, process_id, name) VALUES (102, 100, 'worker')")
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                    "VALUES (20, 100, 101, 1, 7, 2), (80, 100, 102, 1, 11, 2)",
            )
            listOf(null, 100L).forEach { right ->
                val snapshot =
                    store.projectCore(
                        ProfileQuery(startNanosInclusive = 0, endNanosExclusive = right, threadIds = setOf(101)),
                    )
                val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
                assertEquals(21, chart.endNanosExclusive)
                assertEquals(21, chart.blocks.maxOf(StackChartBlock::endNanosExclusive))
                assertEquals(2, snapshot.overview.sampleCount)
                assertEquals(10, snapshot.overview.totalEventWeight)
                assertEquals(3, snapshot.sessionOverview.sampleCount)
                assertEquals(80, snapshot.sessionOverview.endNanosInclusive)
            }
        }

    @Test
    fun `right open committed boundary excludes its sample without extending the preceding sample`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                    "VALUES (20, 100, 101, 1, 7, 2)",
            )
            val snapshot = store.projectCore(ProfileQuery(startNanosInclusive = 0, endNanosExclusive = 20))
            val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
            assertEquals(11, chart.endNanosExclusive)
            assertEquals(1, snapshot.overview.sampleCount)
            assertEquals(3, snapshot.flameGraph.totalWeight)
            assertEquals(2, snapshot.sessionOverview.sampleCount)
        }

    @Test
    fun `user timings keep unsearched markers when marker search excludes every marker`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            val snapshot = store.projectCore(ProfileProjectionRequest(markerSearch = "absent"))
            val markers = assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(snapshot.markers).value
            assertTrue(markers.markers.isEmpty())
            val timings = assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(snapshot.userTimings).value
            assertEquals(listOf("marker"), timings.markers.map(MarkerProjectionRow::name))
            assertEquals(MarkerAvailability.AVAILABLE, timings.availability)
        }

    @Test
    fun `blank marker search reuses its projection for user timings without a duplicate query`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            listOf("", "   ").forEach { search ->
                val queries = mutableListOf<String>()
                val snapshot =
                    SQLiteProfileProjectionQueries.project(
                        store,
                        ProfileProjectionRequest(markerSearch = search),
                        markerProjector = { connection, query, text ->
                            queries += text
                            SQLiteMarkerProjectionQueries.load(connection, query, text)
                        },
                    )
                assertEquals(listOf(search), queries)
                assertSame(snapshot.markers, snapshot.userTimings)
            }
        }

    @Test
    fun `searched markers and unsearched user timings isolate their failures independently`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            val markerFailure =
                SQLiteProfileProjectionQueries.project(
                    store,
                    ProfileProjectionRequest(markerSearch = "marker"),
                    markerProjector = { connection, query, text ->
                        if (text.isNotBlank()) throw SQLException("search failed")
                        SQLiteMarkerProjectionQueries.load(connection, query, text)
                    },
                )
            assertIs<PanelProjection.Failed>(markerFailure.markers)
            assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(markerFailure.userTimings)
            val timingsFailure =
                SQLiteProfileProjectionQueries.project(
                    store,
                    ProfileProjectionRequest(markerSearch = "marker"),
                    markerProjector = { connection, query, text ->
                        if (text.isBlank()) throw SQLException("unsearched markers failed")
                        SQLiteMarkerProjectionQueries.load(connection, query, text)
                    },
                )
            assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(timingsFailure.markers)
            assertIs<PanelProjection.Failed>(timingsFailure.userTimings)
        }

    @Test
    fun `unsearched user timings preserve empty v2 marker availability`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            val snapshot = store.projectCore(ProfileProjectionRequest(markerSearch = "timing"))
            val timings = assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(snapshot.userTimings).value
            assertEquals(MarkerAvailability.AVAILABLE, timings.availability)
            assertEquals(MarkerEmptyReason.PROFILE_EMPTY, timings.emptyReason)
            assertTrue(timings.markers.isEmpty())
            assertEquals(snapshot.markers, snapshot.userTimings)
        }

    @Test
    fun `stack chart projection always receives forward direction while preserving shared filters`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()
            val analysis =
                CallStackAnalysisQuery(
                    direction = CallStackDirection.INVERTED,
                    previewRange = AnalysisTimeRange(10, 11),
                    searchText = "leaf",
                    transforms = listOf(CallStackTransform.FocusFunction(store.functionAt(1))),
                )
            val snapshot =
                SQLiteProfileProjectionQueries.project(
                    store,
                    ProfileProjectionRequest(callStackAnalysis = analysis),
                    stackChartProjector = { source, query, end ->
                        assertEquals(analysis.copy(direction = CallStackDirection.FORWARD, previewRange = null), query)
                        StackChartProjector.project(source, query, end)
                    },
                )
            val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
            assertEquals(
                listOf("root", "leaf"),
                chart.blocks.sortedBy(StackChartBlock::depth).map { chart.framesById.getValue(it.frameId).symbolName },
            )
            assertEquals("leaf", snapshot.callTree.single { it.parentId == null }.symbolName)
            assertEquals(CallStackDirection.INVERTED, snapshot.flameGraph.query.direction)
        }

    @Test
    fun `core projection publishes stack chart and marker snapshots`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            store.attachTwoFrameStack()

            val snapshot = store.projectCore(ProfileProjectionRequest(markerSearch = "marker"))

            assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart)
            assertEquals(
                listOf("marker"),
                assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(snapshot.markers)
                    .value.markers
                    .map(MarkerProjectionRow::name),
            )
        }

    @Test
    fun `marker projection failure preserves sample panels`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()

            val snapshot =
                SQLiteProfileProjectionQueries.project(
                    store = store,
                    request = ProfileProjectionRequest(),
                    markerProjector = { _, _, _ -> throw SQLException("marker unavailable") },
                )

            assertTrue(snapshot.flameGraph.callNodes.size > 0)
            assertEquals("MARKER_QUERY_FAILED", assertIs<PanelProjection.Failed>(snapshot.markers).code)
        }

    @Test
    fun `stack chart failure preserves sample and marker panels`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()

            val snapshot =
                SQLiteProfileProjectionQueries.project(
                    store = store,
                    request = ProfileProjectionRequest(),
                    stackChartProjector = { _, _, _ -> error("stack chart unavailable") },
                )

            assertTrue(snapshot.flameGraph.callNodes.size > 0)
            assertEquals("STACK_CHART_QUERY_FAILED", assertIs<PanelProjection.Failed>(snapshot.stackChart).code)
            assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(snapshot.markers)
        }

    @Test
    fun `top functions use the shared script stack filter`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()
            val request =
                ProfileProjectionRequest(
                    callStackAnalysis =
                        CallStackAnalysisQuery(
                            implementation = ImplementationFilter.SCRIPT,
                        ),
                )

            val topFunctions = store.projectCore(request).topFunctions

            assertEquals(listOf("managedFrame"), topFunctions.map(TopFunction::symbolName))
        }

    @Test
    fun `transforms see original implementation paths across all stack panels`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()
            val transforms =
                listOf(
                    CallStackTransform.FocusFunction(store.functionAt(1)),
                    CallStackTransform.FocusCallNode(CallNodePath(listOf(store.functionAt(1), store.functionAt(2)))),
                )

            transforms.forEach { transform ->
                val snapshot =
                    store.projectCore(
                        ProfileProjectionRequest(
                            callStackAnalysis =
                                CallStackAnalysisQuery(
                                    implementation = ImplementationFilter.SCRIPT,
                                    searchText = "managedFrame,managed.jar",
                                    transforms = listOf(transform),
                                ),
                        ),
                    )
                val node = snapshot.callTree.single()
                val stack = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
                val block = stack.blocks.single()

                assertEquals("managedFrame", node.symbolName)
                assertEquals(0, node.depth)
                assertEquals(node.id, assertNotNull(block.callNodeId).value)
                assertNotNull(snapshot.flameGraph.callNodes.indexOf(assertNotNull(block.callNodeId)))
                assertTrue(snapshot.flameGraph.invalidTransforms.isEmpty())
                assertEquals(listOf("managedFrame"), snapshot.topFunctions.map(TopFunction::symbolName))
                assertEquals(3, snapshot.flameGraph.totalWeight)
            }
        }

    @Test
    fun `script call node transforms retain their creation context before implementation filtering`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()
            val path = CallNodePath(listOf(store.functionAt(2)))
            val focus = CallStackTransform.FocusCallNode(path, ImplementationFilter.SCRIPT)
            val merge = CallStackTransform.MergeCallNode(path, ImplementationFilter.SCRIPT)
            val script = CallStackAnalysisQuery(implementation = ImplementationFilter.SCRIPT)

            val focused =
                store.projectCore(ProfileProjectionRequest(callStackAnalysis = script.copy(transforms = listOf(focus))))
            val merged =
                store.projectCore(ProfileProjectionRequest(callStackAnalysis = script.copy(transforms = listOf(merge))))
            val allFrames =
                store.projectCore(
                    ProfileProjectionRequest(
                        callStackAnalysis =
                            script.copy(implementation = ImplementationFilter.ALL, transforms = listOf(merge)),
                    ),
                )

            assertTrue(focused.flameGraph.invalidTransforms.isEmpty())
            assertEquals("managedFrame", focused.callTree.single().symbolName)
            val stack = assertIs<PanelProjection.Ready<StackChartSnapshot>>(focused.stackChart).value
            assertEquals(focused.callTree.single().id, assertNotNull(stack.blocks.single().callNodeId).value)
            assertTrue(merged.flameGraph.invalidTransforms.isEmpty())
            assertEquals(FlameGraphEmptyReason.IMPLEMENTATION_FILTERED_ALL, merged.flameGraph.emptyReason)
            assertTrue(merged.callTree.isEmpty())
            assertTrue(assertIs<PanelProjection.Ready<StackChartSnapshot>>(merged.stackChart).value.blocks.isEmpty())
            assertTrue(allFrames.flameGraph.invalidTransforms.isEmpty())
            assertEquals("nativeFrame", allFrames.callTree.single().symbolName)
        }

    @Test
    fun `search only matches frames remaining after transforms`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()

            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        callStackAnalysis =
                            CallStackAnalysisQuery(
                                searchText = "managedFrame",
                                transforms = listOf(CallStackTransform.MergeFunction(store.functionAt(2))),
                            ),
                    ),
                )

            assertEquals(FlameGraphEmptyReason.SEARCH_FILTERED_ALL, snapshot.flameGraph.emptyReason)
            assertEquals(0, snapshot.flameGraph.totalWeight)
            assertTrue(snapshot.callTree.isEmpty())
            assertTrue(snapshot.topFunctions.isEmpty())
            val stack = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
            assertEquals(StackChartEmptyReason.FILTERED_ALL, stack.emptyReason)
            assertTrue(stack.blocks.isEmpty())
        }

    @Test
    fun `transform elimination wins over downstream empty implementation and search`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()
            val query =
                CallStackAnalysisQuery(
                    implementation = ImplementationFilter.SCRIPT,
                    searchText = "absent",
                    transforms = listOf(CallStackTransform.DropFunction(store.functionAt(1))),
                )

            val transformed = store.projectCore(ProfileProjectionRequest(callStackAnalysis = query))
            val previewEmpty =
                store.projectCore(
                    ProfileProjectionRequest(
                        callStackAnalysis = query.copy(previewRange = AnalysisTimeRange(20, 30)),
                    ),
                )

            assertEquals(FlameGraphEmptyReason.TRANSFORMS_FILTERED_ALL, transformed.flameGraph.emptyReason)
            assertEquals(FlameGraphEmptyReason.PREVIEW_RANGE_EMPTY, previewEmpty.flameGraph.emptyReason)
        }

    @Test
    fun `preview excluded call paths are invalid before implementation and search`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()
            store.execute("UPDATE sample SET leaf_callsite_id=1")
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                    "VALUES (20, 100, 101, 1, 7, 2)",
            )
            val transform =
                CallStackTransform.FocusCallNode(CallNodePath(listOf(store.functionAt(1), store.functionAt(2))))
            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        callStackAnalysis =
                            CallStackAnalysisQuery(
                                previewRange = AnalysisTimeRange(10, 20),
                                searchText = "Frame",
                                transforms = listOf(transform),
                            ),
                    ),
                )

            assertEquals(listOf(transform), snapshot.flameGraph.invalidTransforms)
            assertEquals("nativeFrame", snapshot.callTree.single().symbolName)
            assertEquals(3, snapshot.flameGraph.totalWeight)
            val stack = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
            assertEquals("managedFrame", stack.framesById.getValue(stack.blocks.single().frameId).symbolName)
            assertEquals(20, stack.blocks.single().startNanos)
            assertEquals(21, stack.blocks.single().endNanosExclusive)
        }

    @Test
    fun `preview filters aggregates without clipping committed stack chart blocks`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                    "VALUES (20, 100, 101, 1, 7, 2)",
            )

            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(startNanosInclusive = 0, endNanosExclusive = 31),
                        callStackAnalysis =
                            CallStackAnalysisQuery(
                                previewRange = AnalysisTimeRange(10, 11),
                                implementation = ImplementationFilter.SCRIPT,
                                searchText = "managedFrame",
                                direction = CallStackDirection.INVERTED,
                                transforms = listOf(CallStackTransform.FocusFunction(store.functionAt(1))),
                            ),
                    ),
                )
            val stack = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
            val block = stack.blocks.single()
            val nodeId = assertNotNull(block.callNodeId)

            assertEquals(2, snapshot.overview.sampleCount)
            assertEquals(3, snapshot.flameGraph.totalWeight)
            assertEquals(3, snapshot.topFunctions.single().inclusiveWeight)
            assertEquals(nodeId.value, snapshot.callTree.single().id)
            assertNotNull(snapshot.flameGraph.callNodes.indexOf(nodeId))
            assertEquals(10, block.startNanos)
            assertEquals(21, block.endNanosExclusive)
            assertEquals(10, block.weight)
            assertEquals(21, stack.endNanosExclusive)
        }

    @Test
    fun `transform ordering preserves exact rejected sample intervals and panel node ids`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachMixedImplementationStack()
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                    "VALUES (20, 100, 101, 1, 5, 1), (30, 100, 101, 1, 7, 2), (40, 100, 101, 1, 11, 1)",
            )
            val query =
                CallStackAnalysisQuery(
                    implementation = ImplementationFilter.SCRIPT,
                    searchText = "managedFrame",
                    transforms = listOf(CallStackTransform.FocusFunction(store.functionAt(1))),
                )

            CallStackDirection.entries.forEach { direction ->
                val snapshot =
                    store.projectCore(
                        ProfileProjectionRequest(callStackAnalysis = query.copy(direction = direction)),
                    )
                val stack = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
                val nodeId = assertNotNull(snapshot.flameGraph.callNodes.nodeIdAt(0))

                assertEquals(listOf(10L to 20L, 30L to 40L), stack.blocks.map { it.startNanos to it.endNanosExclusive })
                assertEquals(listOf(3L, 7L), stack.blocks.map(StackChartBlock::weight))
                assertEquals(listOf(nodeId, nodeId), stack.blocks.map(StackChartBlock::callNodeId))
                assertEquals(nodeId.value, snapshot.callTree.single().id)
                assertEquals(10, snapshot.flameGraph.totalWeight)
            }
        }

    @Test
    fun `top functions count a recursive function once per sample`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachRecursiveFunctionStack()

            val function = store.projectCore().topFunctions.single()

            assertEquals("recursive", function.symbolName)
            assertEquals(3, function.inclusiveWeight)
            assertEquals(3, function.exclusiveWeight)
            assertEquals(1, function.sampleCount)
            assertEquals(1, function.threadCount)
        }

    @Test
    fun `optional panel cancellation and SQLite interrupt abort projection`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()

            assertFailsWith<CancellationException> {
                SQLiteProfileProjectionQueries.project(
                    store = store,
                    request = ProfileProjectionRequest(),
                    stackChartProjector = { _, _, _ -> throw CancellationException("obsolete") },
                )
            }
            assertFailsWith<SQLException> {
                SQLiteProfileProjectionQueries.project(
                    store = store,
                    request = ProfileProjectionRequest(),
                    markerProjector = { _, _, _ -> throw SQLException("interrupted", null, 9) },
                )
            }
            assertTrue(store.connection.autoCommit)
        }

    @Test
    fun `call tree replaces opaque vendor hashes with Firefox file offset labels`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()
            store.execute("UPDATE file SET path='/vendor/lib64/egl/libGLESv2_adreno.so' WHERE file_id=1")
            store.execute(
                "UPDATE symbol SET name='!!!0000!28254c066fd778faffa7894b1bd8b1!0c393b63cf!' " +
                    "WHERE symbol_id=2",
            )

            val snapshot = store.projectCore()
            val projectedLeaf =
                snapshot.callTree.single {
                    it.filePath.endsWith("libGLESv2_adreno.so") && it.depth == 1
                }
            val directLeaf = store.callTree(direction = CallStackDirection.FORWARD).single { it.depth == 1 }

            assertEquals("libGLESv2_adreno.so+0x2", projectedLeaf.symbolName)
            assertEquals("libGLESv2_adreno.so+0x2", directLeaf.symbolName)
            assertEquals(
                "libGLESv2_adreno.so+0x2",
                snapshot.flameGraph.callNodes.framesById.values
                    .single { it.virtualAddress == 2L }
                    .symbolName,
            )
        }

    @Test
    fun `projection distinguishes incomplete stacks from projector failures`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)

            val incomplete = store.projectCore(ProfileQuery(threadIds = setOf(101)))
            assertEquals(FlameGraphEmptyReason.PROFILE_INCOMPLETE, incomplete.flameGraph.emptyReason)

            store.attachTwoFrameStack()
            store.execute("UPDATE sample SET event_count=-1")
            val failed = store.projectCore(ProfileQuery(threadIds = setOf(101)))
            assertEquals(FlameGraphEmptyReason.PROJECTION_FAILED, failed.flameGraph.emptyReason)
            assertTrue(
                failed.flameGraph.diagnosticDetails
                    .orEmpty()
                    .contains("Negative call-stack weight"),
            )
        }

    @Test
    fun `canonical projection distinguishes an empty committed range from a thread with no samples`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)

            val outOfRange =
                store.projectCore(
                    ProfileQuery(
                        startNanosInclusive = 200,
                        endNanosExclusive = 300,
                        threadIds = setOf(101),
                    ),
                )
            val emptyThread =
                store.projectCore(
                    ProfileQuery(
                        startNanosInclusive = 200,
                        endNanosExclusive = 300,
                        threadIds = setOf(202),
                    ),
                )

            assertEquals(FlameGraphEmptyReason.COMMITTED_RANGE_EMPTY, outOfRange.flameGraph.emptyReason)
            assertEquals(FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES, emptyThread.flameGraph.emptyReason)
        }

    @Test
    fun `flame and call tree share one filtered transformed direction projection`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()

            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(threadIds = setOf(101)),
                        callStackAnalysis =
                            CallStackAnalysisQuery(
                                previewRange = AnalysisTimeRange(10, 11),
                                searchText = "root,leaf",
                                direction = CallStackDirection.INVERTED,
                            ),
                    ),
                )

            assertEquals(
                snapshot.callTree.map(CallTreeNode::id),
                snapshot.flameGraph.callNodes.ids
                    .toList(),
            )
            assertEquals("leaf", snapshot.callTree.single { it.parentId == null }.symbolName)
            assertEquals(3L, snapshot.flameGraph.totalWeight)
            assertEquals(false, snapshot.flameGraph.rows.startsAtBottom)

            val previewEmpty =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(threadIds = setOf(101)),
                        callStackAnalysis = CallStackAnalysisQuery(previewRange = AnalysisTimeRange(11, 12)),
                    ),
                )
            assertEquals(1L, previewEmpty.overview.sampleCount)
            assertEquals(0L, previewEmpty.flameGraph.totalWeight)
        }

    @Test
    fun `projection request keeps session aggregates unfiltered from the panel query`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.execute("INSERT INTO thread(thread_id, process_id, name) VALUES (102, 100, 'worker')")
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count) " +
                    "VALUES (20, 100, 102, 1, 7)",
            )

            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(threadIds = setOf(101)),
                        timelineBucketCount = 3,
                        topFunctionLimit = 1,
                        callStackAnalysis = CallStackAnalysisQuery(direction = CallStackDirection.INVERTED),
                    ),
                )

            assertEquals(2L, snapshot.sessionOverview.sampleCount)
            assertEquals(1L, snapshot.overview.sampleCount)
            assertEquals(listOf(101, 102), snapshot.sessionThreads.map(ThreadSummary::threadId).sorted())
            assertEquals(listOf(101), snapshot.threads.map(ThreadSummary::threadId))
            assertEquals(listOf(101, 102), snapshot.timelineTracks.map(ThreadTimelineTrack::threadId).sorted())
            assertEquals(
                listOf(3L, 7L),
                snapshot.timelineTracks.sortedBy(ThreadTimelineTrack::threadId).map { track ->
                    track.buckets.sumOf(TimelineBucket::eventWeight)
                },
            )
        }

    @Test
    fun `core projection groups tracks and preserves availability`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = true, contextSwitchNanos = 11)

            val snapshot = store.projectCore(ProfileQuery(threadIds = setOf(101)))

            assertEquals(
                listOf(ProfileTrackKind.CPU_SAMPLES, ProfileTrackKind.CONTEXT_SWITCHES),
                snapshot.tracks.map(ProfileTrackSnapshot::kind),
            )
            assertEquals(
                listOf(ProfileDataAvailability.AVAILABLE, ProfileDataAvailability.AVAILABLE),
                snapshot.tracks.map(ProfileTrackSnapshot::availability),
            )
            assertEquals(1L, snapshot.overview.sampleCount)
            assertEquals(100, snapshot.tracks.first().processId)
            assertEquals(101, snapshot.tracks.first().threadId)
            assertTrue(store.connection.autoCommit)
        }

    @Test
    fun `projection distinguishes empty collected data from data not collected`() {
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = true, contextSwitchNanos = null)

            val snapshot =
                store.projectCore(
                    ProfileQuery(
                        startNanosInclusive = 20,
                        endNanosExclusive = 30,
                        threadIds = setOf(101),
                    ),
                )

            assertEquals(
                listOf(ProfileDataAvailability.EMPTY, ProfileDataAvailability.EMPTY),
                snapshot.tracks.map(ProfileTrackSnapshot::availability),
            )
        }
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)

            val contextSwitches =
                store
                    .projectCore(ProfileQuery(threadIds = setOf(101)))
                    .tracks
                    .single { it.kind == ProfileTrackKind.CONTEXT_SWITCHES }

            assertEquals(ProfileDataAvailability.NOT_COLLECTED, contextSwitches.availability)
            assertEquals(null, contextSwitches.startNanos)
            assertEquals(null, contextSwitches.endNanosExclusive)
        }
    }

    @Test
    fun `stored context switch evidence overrides stale collection metadata`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = 11)

            val contextSwitches =
                store
                    .projectCore(ProfileQuery(threadIds = setOf(101)))
                    .tracks
                    .single { it.kind == ProfileTrackKind.CONTEXT_SWITCHES }

            assertEquals(ProfileDataAvailability.AVAILABLE, contextSwitches.availability)
            assertEquals(11, contextSwitches.startNanos)
            assertEquals(12, contextSwitches.endNanosExclusive)
        }

    @Test
    fun `projection orders source colliding canonical tracks by stable qualified identity`() =
        withStore { store ->
            store.seedCanonicalSource("z-source", "z-thread", sampleNanos = 12)
            store.seedCanonicalSource("a-source", "a-thread", sampleNanos = 10)

            val first = store.projectCore(ProfileQuery(threadIds = setOf(101)))
            val second = store.projectCore(ProfileQuery(threadIds = setOf(101)))

            assertEquals(first, second)
            assertEquals(
                listOf(
                    "cpu-samples:canonical:a-source:100:101",
                    "cpu-samples:canonical:z-source:100:101",
                    "markers:a-source:100:101",
                    "markers:z-source:100:101",
                    "counters:a-source",
                    "counters:z-source",
                    "slices:a-source:100:101",
                    "slices:z-source:100:101",
                    "screenshots:a-source",
                    "screenshots:z-source",
                ),
                first.tracks.map(ProfileTrackSnapshot::id),
            )
            assertEquals(listOf("a-thread", "z-thread"), first.threads.map(ThreadSummary::name))
            assertTrue(first.tracks.all { it.availability == ProfileDataAvailability.AVAILABLE })
        }

    @Test
    fun `canonical source named legacy cannot collide with a legacy track identity`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.seedCanonicalSource("legacy", "canonical", sampleNanos = 12)

            assertEquals(
                listOf(
                    "cpu-samples:canonical:legacy:100:101",
                    "cpu-samples:legacy:100:101",
                ),
                store
                    .projectCore(ProfileQuery(threadIds = setOf(101)))
                    .tracks
                    .filter { it.kind == ProfileTrackKind.CPU_SAMPLES }
                    .map(ProfileTrackSnapshot::id),
            )
        }

    @Test
    fun `projection freezes caller owned query sets`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            val threadIds = mutableSetOf(101)
            val eventTypes = mutableSetOf("cpu-cycles")

            val snapshot = store.projectCore(ProfileQuery(threadIds = threadIds, eventTypes = eventTypes))
            threadIds += 999
            eventTypes.clear()

            assertEquals(setOf(101), snapshot.query.threadIds)
            assertEquals(setOf("cpu-cycles"), snapshot.query.eventTypes)
        }

    @Test
    fun `interval marker and slice overlap a range while point markers retain point semantics`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            val threadRowId = store.threadRowId("source")
            store.insertMarker("source", threadRowId, startNanos = 5, endNanos = 15)
            store.insertSlice("source", threadRowId, startNanos = 5, endNanos = 15)
            store.insertMarker("source", threadRowId, startNanos = 30, endNanos = null)

            val overlapping =
                store.projectCore(
                    ProfileQuery(startNanosInclusive = 10, endNanosExclusive = 20, threadIds = setOf(101)),
                )

            assertEquals(
                ProfileDataAvailability.AVAILABLE,
                overlapping.tracks.single { it.kind == ProfileTrackKind.MARKERS }.availability,
            )
            assertEquals(
                ProfileDataAvailability.AVAILABLE,
                overlapping.tracks.single { it.kind == ProfileTrackKind.SLICES }.availability,
            )

            val pointOnly =
                store.projectCore(
                    ProfileQuery(startNanosInclusive = 30, endNanosExclusive = 31, threadIds = setOf(101)),
                )
            assertEquals(30, pointOnly.tracks.single { it.kind == ProfileTrackKind.MARKERS }.startNanos)
            assertEquals(31, pointOnly.tracks.single { it.kind == ProfileTrackKind.MARKERS }.endNanosExclusive)

            val afterPoint =
                store.projectCore(
                    ProfileQuery(startNanosInclusive = 31, endNanosExclusive = 32, threadIds = setOf(101)),
                )
            assertEquals(
                ProfileDataAvailability.EMPTY,
                afterPoint.tracks.single { it.kind == ProfileTrackKind.MARKERS }.availability,
            )
        }

    @Test
    fun `marker bounds saturate each point before selecting the track maximum`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            val threadRowId = store.threadRowId("source")
            store.insertMarker("source", threadRowId, startNanos = 10, endNanos = 20)
            store.insertMarker("source", threadRowId, startNanos = 30, endNanos = null)

            val marker =
                store
                    .projectCore(
                        ProfileQuery(startNanosInclusive = 0, endNanosExclusive = 40, threadIds = setOf(101)),
                    ).tracks
                    .single { it.kind == ProfileTrackKind.MARKERS }

            assertEquals(10, marker.startNanos)
            assertEquals(31, marker.endNanosExclusive)
        }

    @Test
    fun `top function boundary selection is deterministic before projection limit`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.seedEqualWeightFunctions(count = 201)

            val topFunctions = store.projectCore(ProfileQuery(threadIds = setOf(101))).topFunctions

            assertEquals(200, topFunctions.size)
            assertEquals(
                (1..200).map { "/functions/${it.toString().padStart(3, '0')}.so" },
                topFunctions.map { it.filePath },
            )
        }

    @Test
    fun `projection rejects an active import transaction`() =
        withStore { store ->
            store.beginRecordImport().use {
                assertFailsWith<IllegalStateException> { store.projectCore() }
                assertTrue(!store.connection.autoCommit)
            }
            assertTrue(store.connection.autoCommit)
        }

    @Test
    fun `projection failure rolls back and restores auto commit`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.execute("DROP TABLE profile_marker")

            assertFailsWith<SQLException> { store.projectCore() }

            assertTrue(store.connection.autoCommit)
        }

    private fun SQLiteSampleStore.seedLegacyProfile(
        traceOffCpu: Boolean,
        contextSwitchNanos: Long?,
    ) {
        execute("INSERT INTO process(process_id, name) VALUES (100, 'app')")
        execute("INSERT INTO thread(thread_id, process_id, name) VALUES (101, 100, 'main')")
        execute("INSERT INTO event(event_id, name) VALUES (1, 'cpu-cycles')")
        execute(
            "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count) " +
                "VALUES (10, 100, 101, 1, 3)",
        )
        execute(
            "INSERT INTO profile_metadata(metadata_id, event_types, trace_off_cpu) " +
                "VALUES (1, 'cpu-cycles', ${if (traceOffCpu) 1 else 0})",
        )
        contextSwitchNanos?.let { timestamp ->
            execute(
                "INSERT INTO context_switch(thread_id, timestamp_nanos, switched_on_cpu) " +
                    "VALUES (101, $timestamp, 1)",
            )
        }
    }

    private fun SQLiteSampleStore.functionAt(frameId: Long): FlameFunctionId =
        projectCore()
            .flameGraph.callNodes.framesById
            .getValue(frameId)
            .functionId

    private fun SQLiteSampleStore.attachTwoFrameStack() {
        execute("INSERT INTO file(file_id, path) VALUES (1, '/lib.so')")
        execute("INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) VALUES (1, 1, 1, 'root')")
        execute("INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) VALUES (2, 1, 2, 'leaf')")
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (1, 1, 1, 1, 'NATIVE')",
        )
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (2, 2, 1, 2, 'NATIVE')",
        )
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (1, NULL, 1)")
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (2, 1, 2)")
        execute("UPDATE sample SET leaf_callsite_id=2")
    }

    private fun SQLiteSampleStore.attachMixedImplementationStack() {
        execute("INSERT INTO file(file_id, path) VALUES (1, '/native.so')")
        execute("INSERT INTO file(file_id, path) VALUES (2, '/managed.jar')")
        execute("INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) VALUES (1, 1, 1, 'nativeFrame')")
        execute("INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) VALUES (2, 2, 2, 'managedFrame')")
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (1, 1, 1, 1, 'NATIVE')",
        )
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (2, 2, 2, 2, 'ART')",
        )
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (1, NULL, 1)")
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (2, 1, 2)")
        execute("UPDATE sample SET leaf_callsite_id=2")
    }

    private fun SQLiteSampleStore.attachRecursiveFunctionStack() {
        execute("INSERT INTO file(file_id, path) VALUES (1, '/recursive.so')")
        execute("INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) VALUES (1, 1, 1, 'recursive')")
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (1, 1, 1, 1, 'NATIVE')",
        )
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (2, 2, 1, 1, 'NATIVE')",
        )
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (1, NULL, 1)")
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (2, 1, 2)")
        execute("UPDATE sample SET leaf_callsite_id=2")
    }

    private fun SQLiteSampleStore.seedCanonicalSource(
        sourceId: String,
        threadName: String,
        sampleNanos: Long,
    ) {
        execute(
            "INSERT INTO profile_source(source_id, kind, clock_domain) " +
                "VALUES ('$sourceId', 'SIMPLEPERF', 'monotonic')",
        )
        execute(
            "INSERT INTO profile_process(source_id, process_id, name) " +
                "VALUES ('$sourceId', 100, 'app')",
        )
        val processRowId =
            connection.singleLong("SELECT process_row_id FROM profile_process WHERE source_id='$sourceId'")
        execute(
            "INSERT INTO profile_thread(source_id, process_row_id, thread_id, name) " +
                "VALUES ('$sourceId', $processRowId, 101, '$threadName')",
        )
        val threadRowId = connection.singleLong("SELECT thread_row_id FROM profile_thread WHERE source_id='$sourceId'")
        execute("INSERT OR IGNORE INTO process(process_id, name) VALUES (100, 'app')")
        execute("INSERT OR IGNORE INTO thread(thread_id, process_id, name) VALUES (101, 100, '$threadName')")
        execute("INSERT OR IGNORE INTO event(event_id, name) VALUES (1, 'cpu-cycles')")
        execute(
            "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, " +
                "source_id, process_row_id, thread_row_id, clock_domain, time_error_bound_nanos) " +
                "VALUES ($sampleNanos, 100, 101, 1, 1, '$sourceId', $processRowId, $threadRowId, 'monotonic', 0)",
        )
        execute(
            "INSERT INTO profile_marker(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, schema_name, name, payload_json) " +
                "VALUES ('$sourceId', $threadRowId, $sampleNanos, 'monotonic', 0, 'log', 'marker', '{}')",
        )
        execute(
            "INSERT INTO profile_counter(source_id, timestamp_nanos, clock_domain, time_error_bound_nanos, " +
                "name, unit, value) VALUES ('$sourceId', $sampleNanos, 'monotonic', 0, 'rss', 'bytes', 1.0)",
        )
        execute(
            "INSERT INTO profile_slice(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, name) " +
                "VALUES ('$sourceId', $threadRowId, $sampleNanos, 'monotonic', 0, " +
                "${sampleNanos + 1}, 'monotonic', 0, 'slice')",
        )
        execute(
            "INSERT INTO profile_screenshot(source_id, timestamp_nanos, clock_domain, " +
                "time_error_bound_nanos, artifact_path) " +
                "VALUES ('$sourceId', $sampleNanos, 'monotonic', 0, '$sourceId.png')",
        )
    }

    private fun SQLiteSampleStore.threadRowId(sourceId: String): Long =
        connection.singleLong("SELECT thread_row_id FROM profile_thread WHERE source_id='$sourceId'")

    private fun SQLiteSampleStore.insertMarker(
        sourceId: String,
        threadRowId: Long,
        startNanos: Long,
        endNanos: Long?,
    ) {
        val endColumns =
            if (endNanos == null) {
                "NULL, NULL, NULL"
            } else {
                "$endNanos, 'monotonic', 0"
            }
        execute(
            "INSERT INTO profile_marker(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, " +
                "schema_name, name, payload_json) VALUES ('$sourceId', $threadRowId, $startNanos, " +
                "'monotonic', 0, $endColumns, 'log', 'marker', '{}')",
        )
    }

    private fun SQLiteSampleStore.insertSlice(
        sourceId: String,
        threadRowId: Long,
        startNanos: Long,
        endNanos: Long,
    ) {
        execute(
            "INSERT INTO profile_slice(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, name) " +
                "VALUES ('$sourceId', $threadRowId, $startNanos, 'monotonic', 0, " +
                "$endNanos, 'monotonic', 0, 'slice')",
        )
    }

    private fun SQLiteSampleStore.seedEqualWeightFunctions(count: Int) {
        repeat(count) { offset ->
            val rowId = offset + 1
            val pathIndex = count - offset
            val path = "/functions/${pathIndex.toString().padStart(3, '0')}.so"
            execute("INSERT INTO file(file_id, path) VALUES ($rowId, '$path')")
            execute(
                "INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) " +
                    "VALUES ($rowId, $rowId, 1, 'sameFunction')",
            )
            execute(
                "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                    "VALUES ($rowId, $rowId, $rowId, $rowId, 'NATIVE')",
            )
            execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES ($rowId, NULL, $rowId)")
            execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                    "VALUES (${rowId + 100}, 100, 101, 1, 1, $rowId)",
            )
        }
    }

    private fun SQLiteSampleStore.execute(sql: String) {
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    private fun withStore(block: (SQLiteSampleStore) -> Unit) {
        val database = Files.createTempFile("aps-projection-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use(block)
        } finally {
            deleteDatabase(database)
        }
    }

    private fun deleteDatabase(database: Path) {
        database.deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
    }
}
