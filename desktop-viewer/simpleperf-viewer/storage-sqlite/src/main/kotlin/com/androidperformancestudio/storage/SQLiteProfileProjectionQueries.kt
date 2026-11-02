@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "TooManyFunctions")

package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFilter
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.CallStackTransformer
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import com.androidperformancestudio.profileanalysis.CallTreeProjector
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FlameGraphStageCounts
import com.androidperformancestudio.profileanalysis.StackChartProjector
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.profileanalysis.withWeightType
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CancellationException

internal object SQLiteProfileProjectionQueries {
    fun project(
        store: SQLiteSampleStore,
        query: ProfileQuery,
    ): ProfileProjectionSnapshot = project(store, ProfileProjectionRequest(query = query))

    fun project(
        store: SQLiteSampleStore,
        request: ProfileProjectionRequest,
        stackChartProjector: (CallStackTable, CallStackAnalysisQuery, Long) -> StackChartSnapshot =
            StackChartProjector::project,
        markerProjector: (Connection, ProfileQuery, String) -> MarkerProjectionSnapshot =
            SQLiteMarkerProjectionQueries::load,
    ): ProfileProjectionSnapshot =
        store.readTransaction {
            val frozenQuery = connection.projectionQuery(request)
            val overview = overview(frozenQuery)
            val sessionOverview = overview()
            val sessionThreads = threads().sortedThreads()
            val quality = dataQuality()
            val source = SQLiteFlameGraphStackQueries.load(connection, frozenQuery)
            val tracedTimeSource =
                connection.tracedTimeSource(source, frozenQuery, request.callStackAnalysis.weightType)
            val callStacks =
                projectCallStacks(
                    source,
                    tracedTimeSource,
                    request.copy(query = frozenQuery),
                    sessionOverview,
                    sessionThreads,
                )
            val stackChart =
                isolatedPanel(STACK_CHART_QUERY_FAILED) {
                    // Preview changes the viewport, not the committed stack-chart data.
                    stackChartProjector(
                        source.projectionWeights(request.callStackAnalysis.weightType, tracedTimeSource),
                        request.callStackAnalysis.copy(previewRange = null, direction = CallStackDirection.FORWARD),
                        overview.stackChartViewportEnd(request),
                    )
                }
            val markers =
                isolatedPanel(MARKER_QUERY_FAILED) {
                    markerProjector(connection, frozenQuery, request.markerSearch)
                }
            val userTimings =
                if (request.markerSearch.isBlank()) {
                    markers
                } else {
                    isolatedPanel(USER_TIMINGS_QUERY_FAILED) {
                        markerProjector(connection, frozenQuery, "")
                    }
                }
            ProfileProjectionSnapshot(
                query = frozenQuery,
                flameGraph = callStacks.flameGraph,
                callTree = callStacks.flameGraph.callNodes.toCallTree(),
                overview = overview.copy(eventTypes = overview.eventTypes.sorted()),
                quality = quality.sorted(),
                tracks = coreTracks(connection, frozenQuery),
                threads = threads(frozenQuery).sortedThreads(),
                timeline = timelineBuckets(frozenQuery, request.timelineBucketCount).sortedTimeline(),
                topFunctions = callStacks.projectTopFunctions(request),
                stackChart = stackChart,
                markers = markers,
                userTimings = userTimings,
                sessionOverview = sessionOverview.copy(eventTypes = sessionOverview.eventTypes.sorted()),
                sessionThreads = sessionThreads,
                timelineTracks =
                    threadTimelineTracks(
                        frozenQuery.copy(threadIds = emptySet()),
                        request.timelineBucketCount,
                    ),
            )
        }

    private fun Connection.projectionQuery(request: ProfileProjectionRequest): ProfileQuery {
        val query = request.query.freeze()
        return if (request.selectInitialThread && query.threadIds.isEmpty()) {
            initialThread(query)?.let { query.copy(threadIds = setOf(it)) } ?: query
        } else {
            query
        }
    }

    private fun Connection.tracedTimeSource(
        source: CallStackTable,
        query: ProfileQuery,
        type: CallStackWeightType,
    ): CallStackTable =
        if (type == CallStackWeightType.SAMPLES && query.hasCommittedRange()) {
            SQLiteFlameGraphStackQueries.loadTimingSource(
                this,
                query.copy(startNanosInclusive = null, endNanosExclusive = null),
            )
        } else {
            source
        }

    private fun CallStackPanels.projectTopFunctions(request: ProfileProjectionRequest): List<TopFunction> =
        CallStackTopFunctions.project(
            table = transformed,
            options =
                TopFunctionOptions(
                    limit = request.topFunctionLimit,
                    search = request.topSearch,
                    sort = request.topSort,
                    descending = request.topDescending,
                ),
        )

    private fun SQLiteSampleStore.projectCallStacks(
        source: CallStackTable,
        tracedTimeSource: CallStackTable,
        request: ProfileProjectionRequest,
        sessionOverview: ProfileOverview,
        sessionThreads: List<ThreadSummary>,
    ): CallStackPanels {
        val query = request.query
        val analysisQuery = request.callStackAnalysis
        val preview = CallStackFilter.apply(source, CallStackAnalysisQuery(previewRange = analysisQuery.previewRange))
        // Firefox derives traced time after cropping the preview, but before transforms/search.
        // In particular the last retained sample uses the interval, not a sample outside the preview.
        val weighted = preview.table.projectionWeights(analysisQuery.weightType, tracedTimeSource)
        val transformed = CallStackTransformer.apply(weighted, analysisQuery.transforms)
        val filtered = CallStackFilter.apply(transformed.table, analysisQuery.copy(previewRange = null))
        val projection = CallTreeProjector.projectResult(filtered.table, analysisQuery.direction)
        val callNodes = projection.callNodes
        val committedRangeExcludedSamples =
            source.stacks.isEmpty() &&
                query.hasCommittedRange() &&
                hasOtherwiseEligibleSamples(query, sessionOverview, sessionThreads)
        val selectedThreadHasNoSamples =
            source.stacks.isEmpty() &&
                !committedRangeExcludedSamples &&
                (
                    query.threadIds.isEmpty() ||
                        sessionThreads.none { thread -> thread.threadId in query.threadIds && thread.sampleCount > 0 }
                )
        val stages =
            FlameGraphStageCounts(
                sourceStackCount = source.stacks.size,
                selectedThreadHasNoSamples = selectedThreadHasNoSamples,
                committedRangeExcludedSamples = committedRangeExcludedSamples,
                afterPreviewCount = preview.afterPreviewCount,
                afterSearchCount = filtered.afterSearchCount,
                afterImplementationCount = filtered.afterImplementationCount,
                afterTransformCount = transformed.outputStackCount,
                incompleteStackCount = projection.incompleteStackCount,
                projectedNodeCount = callNodes.size,
                projectionFailure = projection.failureDetail,
            )
        return CallStackPanels(
            transformed = filtered.table,
            flameGraph =
                FlameGraphSnapshot(
                    query = request.callStackAnalysis,
                    callNodes = callNodes,
                    rows = FlameGraphRowProjector.project(callNodes, request.callStackAnalysis.direction),
                    totalWeight = callNodes.rootWeight(),
                    emptyReason = stages.emptyReason(),
                    invalidTransforms = transformed.invalidTransforms,
                    diagnosticDetails = projection.failureDetail,
                    weightType = filtered.table.weightType,
                ),
        )
    }

    private fun CallStackTable.projectionWeights(
        type: CallStackWeightType,
        tracedTimeSource: CallStackTable = this,
    ): CallStackTable =
        // Preserve invalid weights for the projector's structured failure; never turn them into 1.
        if (stacks.any { it.weight < 0 }) copy(weightType = type) else withWeightType(type, tracedTimeSource)

    private fun ProfileOverview.stackChartViewportEnd(request: ProfileProjectionRequest): Long =
        minOf(endNanosInclusive?.exclusiveEnd() ?: 0L, request.query.endNanosExclusive ?: Long.MAX_VALUE)

    private inline fun <T> isolatedPanel(
        code: String,
        project: () -> T,
    ): PanelProjection<T> =
        try {
            PanelProjection.Ready(project())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: SQLException) {
            if (interrupted.errorCode == SQLITE_INTERRUPT) throw interrupted
            PanelProjection.Failed(code, interrupted.message ?: code)
        } catch (failure: Exception) {
            PanelProjection.Failed(code, failure.message ?: code)
        }

    private fun SQLiteSampleStore.hasOtherwiseEligibleSamples(
        query: ProfileQuery,
        sessionOverview: ProfileOverview,
        sessionThreads: List<ThreadSummary>,
    ): Boolean =
        when {
            query.eventTypes.isNotEmpty() ->
                sampleCount(query.copy(startNanosInclusive = null, endNanosExclusive = null)) > 0
            query.threadIds.isEmpty() -> sessionOverview.sampleCount > 0
            else -> sessionThreads.any { thread -> thread.threadId in query.threadIds && thread.sampleCount > 0 }
        }

    private fun ProfileQuery.hasCommittedRange(): Boolean = startNanosInclusive != null || endNanosExclusive != null

    private fun CallNodeTable.rootWeight(): Long {
        val parents = parentIndexes
        val weights = inclusiveWeights
        var total = 0L
        parents.indices.forEach { index ->
            if (parents[index] == -1) total = saturatingAdd(total, weights[index])
        }
        return total
    }

    private fun CallNodeTable.toCallTree(): List<CallTreeNode> {
        val nodeIds = ids
        val parents = parentIndexes
        val nodeFrameIds = frameIds
        val nodeDepths = depths
        val inclusive = inclusiveWeights
        val self = selfWeights
        val samples = sampleCounts
        val threads = threadCounts
        return nodeIds.indices.map { index ->
            val frame = framesById.getValue(nodeFrameIds[index])
            CallTreeNode(
                id = nodeIds[index],
                parentId = parents[index].takeIf { it >= 0 }?.let(nodeIds::get),
                depth = nodeDepths[index],
                symbolName = frame.symbolName,
                filePath = frame.resource,
                inclusiveWeight = inclusive[index],
                exclusiveWeight = self[index],
                sampleCount = samples[index],
                threadCount = threads[index].toLong(),
            )
        }
    }

    private fun saturatingAdd(
        left: Long,
        right: Long,
    ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    fun coreTracks(
        connection: Connection,
        query: ProfileQuery,
    ): List<ProfileTrackSnapshot> =
        buildList {
            val legacy = connection.isLegacySchema()
            val cpuTracks = cpuTracks(connection, query, legacy)
            addAll(cpuTracks.map(CpuTrack::snapshot))
            addAll(contextSwitchTracks(connection, query, cpuTracks))
            if (!legacy) {
                addAll(threadFactTracks(connection, query, ThreadFactKind.MARKER))
                addAll(globalFactTracks(connection, query, GlobalFactKind.COUNTER))
                addAll(threadFactTracks(connection, query, ThreadFactKind.SLICE))
                addAll(globalFactTracks(connection, query, GlobalFactKind.SCREENSHOT))
            }
        }.sortedWith(compareBy<ProfileTrackSnapshot>({ it.kind.ordinal }, ProfileTrackSnapshot::id))

    private fun cpuTracks(
        connection: Connection,
        query: ProfileQuery,
        legacy: Boolean,
    ): List<CpuTrack> {
        val baseFilter = threadFilter(query, "s.thread_id")
        val tracksSql = if (legacy) LEGACY_CPU_TRACKS_SQL else CPU_TRACKS_SQL
        val base =
            connection.prepareStatement(tracksSql.replace("/*FILTER*/", baseFilter.whereClause)).use { statement ->
                statement.bind(baseFilter.parameters)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.cpuTrack())
                    }
                }
            }
        val filtered = sampleBounds(connection, query, legacy)
        return base.map { track ->
            val bounds = filtered[track.key]
            track.copy(
                availability =
                    if (bounds == null) ProfileDataAvailability.EMPTY else ProfileDataAvailability.AVAILABLE,
                startNanos = bounds?.first,
                endNanosExclusive = bounds?.second,
            )
        }
    }

    private fun sampleBounds(
        connection: Connection,
        query: ProfileQuery,
        legacy: Boolean,
    ): Map<String, Pair<Long, Long>> {
        val filter = query.toSqlFilter("s", "e")
        val boundsSql = if (legacy) LEGACY_CPU_BOUNDS_SQL else CPU_BOUNDS_SQL
        return connection
            .prepareStatement(boundsSql.replace("/*FILTER*/", filter.whereClause))
            .use { statement ->
                statement.bind(filter.parameters)
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            put(result.getString(1), result.getLong(2) to result.getLong(3).exclusiveEnd())
                        }
                    }
                }
            }
    }

    private fun contextSwitchTracks(
        connection: Connection,
        query: ProfileQuery,
        cpuTracks: List<CpuTrack>,
    ): List<ProfileTrackSnapshot> {
        val legacyTracks = cpuTracks.filter { it.sourceId == null }
        if (legacyTracks.isEmpty()) return emptyList()
        val collectionRequested =
            connection.singleLong(
                "SELECT COALESCE(MAX(trace_off_cpu), 0) FROM profile_metadata",
            ) != 0L
        val collectedThreadIds = contextSwitchThreadIds(connection)
        val bounds = contextSwitchBounds(connection, query)
        return legacyTracks.map { track ->
            val trackBounds = bounds[track.threadId]
            val collected = collectionRequested || track.threadId in collectedThreadIds
            ProfileTrackSnapshot(
                id = "context-switches:legacy:${track.processId}:${track.threadId}",
                kind = ProfileTrackKind.CONTEXT_SWITCHES,
                processId = track.processId,
                threadId = track.threadId,
                availability =
                    when {
                        !collected -> ProfileDataAvailability.NOT_COLLECTED
                        trackBounds == null -> ProfileDataAvailability.EMPTY
                        else -> ProfileDataAvailability.AVAILABLE
                    },
                startNanos = trackBounds?.first,
                endNanosExclusive = trackBounds?.second,
            )
        }
    }

    private fun contextSwitchThreadIds(connection: Connection): Set<Int> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT DISTINCT thread_id FROM context_switch").use { result ->
                buildSet {
                    while (result.next()) add(result.getInt(1))
                }
            }
        }

    private fun contextSwitchBounds(
        connection: Connection,
        query: ProfileQuery,
    ): Map<Int, Pair<Long, Long>> {
        val filter = timestampFilter(query, "cs.timestamp_nanos", "cs.thread_id")
        val sql =
            "SELECT cs.thread_id, MIN(cs.timestamp_nanos), MAX(cs.timestamp_nanos) FROM context_switch cs " +
                "${filter.whereClause} GROUP BY cs.thread_id"
        return connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                buildMap {
                    while (result.next()) {
                        put(result.getInt(1), result.getLong(2) to result.getLong(3).exclusiveEnd())
                    }
                }
            }
        }
    }

    private fun threadFactTracks(
        connection: Connection,
        query: ProfileQuery,
        kind: ThreadFactKind,
    ): List<ProfileTrackSnapshot> {
        val baseFilter = threadFilter(query, "pt.thread_id")
        val baseSql =
            "SELECT f.source_id, pp.process_id, pt.thread_id, f.thread_row_id FROM ${kind.table} f " +
                "LEFT JOIN profile_thread pt ON pt.thread_row_id=f.thread_row_id " +
                "LEFT JOIN profile_process pp ON pp.process_row_id=pt.process_row_id " +
                "${baseFilter.whereClause} GROUP BY f.source_id, pp.process_id, pt.thread_id, f.thread_row_id"
        val base =
            connection.prepareStatement(baseSql).use { statement ->
                statement.bind(baseFilter.parameters)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                FactTrack(
                                    key = factKey(result.getString(1), result.getNullableLong(4)),
                                    sourceId = result.getString(1),
                                    processId = result.getNullableInt(2),
                                    threadId = result.getNullableInt(3),
                                ),
                            )
                        }
                    }
                }
            }
        val bounds = threadFactBounds(connection, query, kind)
        return base.map { track ->
            val trackBounds = bounds[track.key]
            ProfileTrackSnapshot(
                id = track.id(kind.idPrefix),
                kind = kind.trackKind,
                processId = track.processId,
                threadId = track.threadId,
                availability = trackBounds.availability(),
                startNanos = trackBounds?.first,
                endNanosExclusive = trackBounds?.second,
            )
        }
    }

    private fun threadFactBounds(
        connection: Connection,
        query: ProfileQuery,
        kind: ThreadFactKind,
    ): Map<String, Pair<Long, Long>> {
        val filter = factFilter(query, kind)
        val sql =
            "SELECT f.source_id, f.thread_row_id, MIN(f.start_nanos), " +
                "MAX(${kind.exclusiveEndSql}) FROM ${kind.table} f " +
                "LEFT JOIN profile_thread pt ON pt.thread_row_id=f.thread_row_id " +
                "${filter.whereClause} GROUP BY f.source_id, f.thread_row_id"
        return connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                buildMap {
                    while (result.next()) {
                        val start = result.getLong(3)
                        val end = result.getLong(4)
                        put(factKey(result.getString(1), result.getNullableLong(2)), start to end)
                    }
                }
            }
        }
    }

    private fun globalFactTracks(
        connection: Connection,
        query: ProfileQuery,
        kind: GlobalFactKind,
    ): List<ProfileTrackSnapshot> {
        val sources =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT DISTINCT source_id FROM ${kind.table} ORDER BY source_id")
                    .use { result ->
                        buildList {
                            while (result.next()) add(result.getString(1))
                        }
                    }
            }
        val filter = timestampFilter(query, "timestamp_nanos", null)
        val boundsSql =
            "SELECT source_id, MIN(timestamp_nanos), MAX(timestamp_nanos) FROM ${kind.table} " +
                "${filter.whereClause} GROUP BY source_id"
        val bounds =
            connection.prepareStatement(boundsSql).use { statement ->
                statement.bind(filter.parameters)
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            put(result.getString(1), result.getLong(2) to result.getLong(3).exclusiveEnd())
                        }
                    }
                }
            }
        return sources.map { sourceId ->
            val trackBounds = bounds[sourceId]
            ProfileTrackSnapshot(
                id = "${kind.idPrefix}:$sourceId",
                kind = kind.trackKind,
                processId = null,
                threadId = null,
                availability = trackBounds.availability(),
                startNanos = trackBounds?.first,
                endNanosExclusive = trackBounds?.second,
            )
        }
    }

    private fun threadFilter(
        query: ProfileQuery,
        column: String,
    ): SqlFilter {
        if (query.threadIds.isEmpty()) return SqlFilter("", emptyList())
        return SqlFilter(
            "WHERE $column IN (${query.threadIds.joinToString { "?" }})",
            query.threadIds.sorted(),
        )
    }

    private fun timestampFilter(
        query: ProfileQuery,
        timestampColumn: String,
        threadColumn: String?,
    ): SqlFilter {
        val predicates = mutableListOf<String>()
        val parameters = mutableListOf<Any>()
        query.startNanosInclusive?.let {
            predicates += "$timestampColumn >= ?"
            parameters += it
        }
        query.endNanosExclusive?.let {
            predicates += "$timestampColumn < ?"
            parameters += it
        }
        if (threadColumn != null && query.threadIds.isNotEmpty()) {
            predicates += "$threadColumn IN (${query.threadIds.joinToString { "?" }})"
            parameters.addAll(query.threadIds.sorted())
        }
        return SqlFilter(
            if (predicates.isEmpty()) "" else "WHERE ${predicates.joinToString(" AND ")}",
            parameters,
        )
    }

    private fun factFilter(
        query: ProfileQuery,
        kind: ThreadFactKind,
    ): SqlFilter {
        val predicates = mutableListOf<String>()
        val parameters = mutableListOf<Any>()
        query.startNanosInclusive?.let { start ->
            predicates += kind.lowerBoundSql
            repeat(kind.lowerBoundParameterCount) { parameters += start }
        }
        query.endNanosExclusive?.let { end ->
            predicates += "f.start_nanos < ?"
            parameters += end
        }
        if (query.threadIds.isNotEmpty()) {
            predicates += "pt.thread_id IN (${query.threadIds.joinToString { "?" }})"
            parameters.addAll(query.threadIds.sorted())
        }
        return SqlFilter(
            if (predicates.isEmpty()) "" else "WHERE ${predicates.joinToString(" AND ")}",
            parameters,
        )
    }

    private const val CPU_TRACK_KEY =
        "CASE WHEN s.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
            "ELSE 'canonical:' || s.thread_row_id END"

    private const val CPU_TRACKS_SQL =
        "SELECT $CPU_TRACK_KEY, s.source_id, COALESCE(pp.process_id, t.process_id), " +
            "COALESCE(pt.thread_id, t.thread_id) FROM sample s " +
            "LEFT JOIN profile_thread pt ON pt.thread_row_id=s.thread_row_id " +
            "LEFT JOIN profile_process pp ON pp.process_row_id=s.process_row_id " +
            "LEFT JOIN thread t ON t.thread_id=s.thread_id /*FILTER*/ " +
            "GROUP BY $CPU_TRACK_KEY, s.source_id, COALESCE(pp.process_id, t.process_id), " +
            "COALESCE(pt.thread_id, t.thread_id)"

    private const val CPU_BOUNDS_SQL =
        "SELECT $CPU_TRACK_KEY, MIN(s.timestamp_nanos), MAX(s.timestamp_nanos) FROM sample s " +
            "JOIN event e ON e.event_id=s.event_id /*FILTER*/ GROUP BY $CPU_TRACK_KEY"

    private const val LEGACY_CPU_TRACKS_SQL =
        "SELECT 'legacy:' || s.thread_id, NULL, t.process_id, t.thread_id FROM sample s " +
            "JOIN thread t ON t.thread_id=s.thread_id /*FILTER*/ " +
            "GROUP BY s.thread_id, t.process_id, t.thread_id"

    private const val LEGACY_CPU_BOUNDS_SQL =
        "SELECT 'legacy:' || s.thread_id, MIN(s.timestamp_nanos), MAX(s.timestamp_nanos) FROM sample s " +
            "JOIN event e ON e.event_id=s.event_id /*FILTER*/ GROUP BY s.thread_id"

    private const val STACK_CHART_QUERY_FAILED = "STACK_CHART_QUERY_FAILED"
    private const val MARKER_QUERY_FAILED = "MARKER_QUERY_FAILED"
    private const val USER_TIMINGS_QUERY_FAILED = "USER_TIMINGS_QUERY_FAILED"
    private const val SQLITE_INTERRUPT = 9
}

private fun Connection.initialThread(query: ProfileQuery): Int? {
    val filter = query.toSqlFilter("s", "e")
    val eligible = "s.leaf_callsite_id IS NOT NULL"
    val where = if (filter.whereClause.isEmpty()) "WHERE $eligible" else "${filter.whereClause} AND $eligible"
    val sql =
        "SELECT s.thread_id, COUNT(*) FROM sample s LEFT JOIN event e ON e.event_id=s.event_id " +
            "$where GROUP BY s.thread_id"
    val counts =
        prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getInt(1), rows.getLong(2)) }
            }
        }
    val threads =
        prepareStatement("SELECT thread_id, process_id, name FROM thread ORDER BY process_id, thread_id")
            .use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val id = rows.getInt(1)
                            val count = counts[id] ?: 0
                            if (count > 0) add(ThreadSummary(rows.getInt(2), id, rows.getString(3), count, 0))
                        }
                    }
                }
            }
    return threads
        .sortedWith(
            compareBy<ThreadSummary>(
                { it.name == "Compositor" || it.name == "Renderer" },
                {
                    val boost = if (INITIAL_MEDIA_THREAD.matches(it.name)) MEDIA_ACTIVITY_BOOST else 1.0
                    -it.sampleCount.toDouble() * boost
                },
                ThreadSummary::processId,
                ThreadSummary::threadId,
            ),
        ).firstOrNull()
        ?.threadId
}

private val INITIAL_MEDIA_THREAD = Regex("^(Audio|Media|GraphRunner|WebrtcWorker).*")
private const val MEDIA_ACTIVITY_BOOST = 40.0

private data class CallStackPanels(
    val transformed: CallStackTable,
    val flameGraph: FlameGraphSnapshot,
)

private inline fun <T> SQLiteSampleStore.readTransaction(block: SQLiteSampleStore.() -> T): T {
    check(connection.autoCommit) { "A record import is active" }
    connection.autoCommit = false
    try {
        return block()
    } finally {
        try {
            connection.rollback()
        } finally {
            connection.autoCommit = true
        }
    }
}

private fun DataQualitySummary.sorted(): DataQualitySummary =
    copy(
        unwindErrors =
            unwindErrors.sortedWith(
                compareByDescending<UnwindErrorSummary> { it.sampleCount }
                    .thenBy(UnwindErrorSummary::code)
                    .thenBy(UnwindErrorSummary::rawCode)
                    .thenBy(UnwindErrorSummary::address),
            ),
    )

private fun List<ThreadSummary>.sortedThreads(): List<ThreadSummary> =
    sortedWith(
        compareByDescending<ThreadSummary> { it.totalEventCount }
            .thenBy(ThreadSummary::threadId)
            .thenBy(ThreadSummary::processId)
            .thenBy(ThreadSummary::name)
            .thenByDescending(ThreadSummary::sampleCount),
    )

private fun List<TimelineBucket>.sortedTimeline(): List<TimelineBucket> =
    sortedWith(
        compareBy(
            TimelineBucket::startNanos,
            TimelineBucket::endNanosExclusive,
            TimelineBucket::sampleCount,
            TimelineBucket::eventWeight,
        ),
    )

private data class CpuTrack(
    val key: String,
    val sourceId: String?,
    val processId: Int,
    val threadId: Int,
    val availability: ProfileDataAvailability = ProfileDataAvailability.EMPTY,
    val startNanos: Long? = null,
    val endNanosExclusive: Long? = null,
) {
    fun snapshot(): ProfileTrackSnapshot =
        ProfileTrackSnapshot(
            id =
                if (sourceId == null) {
                    "cpu-samples:legacy:$processId:$threadId"
                } else {
                    "cpu-samples:canonical:$sourceId:$processId:$threadId"
                },
            kind = ProfileTrackKind.CPU_SAMPLES,
            processId = processId,
            threadId = threadId,
            availability = availability,
            startNanos = startNanos,
            endNanosExclusive = endNanosExclusive,
        )
}

private data class FactTrack(
    val key: String,
    val sourceId: String,
    val processId: Int?,
    val threadId: Int?,
) {
    fun id(prefix: String): String =
        if (threadId == null) {
            "$prefix:$sourceId:global"
        } else {
            "$prefix:$sourceId:$processId:$threadId"
        }
}

private enum class ThreadFactKind(
    val table: String,
    val idPrefix: String,
    val trackKind: ProfileTrackKind,
    val exclusiveEndSql: String,
    val lowerBoundSql: String,
    val lowerBoundParameterCount: Int,
) {
    MARKER(
        table = "profile_marker",
        idPrefix = "markers",
        trackKind = ProfileTrackKind.MARKERS,
        exclusiveEndSql =
            "CASE WHEN f.end_nanos IS NOT NULL THEN f.end_nanos " +
                "WHEN f.start_nanos = 9223372036854775807 THEN f.start_nanos ELSE f.start_nanos + 1 END",
        lowerBoundSql =
            "((f.end_nanos IS NULL AND f.start_nanos >= ?) OR " +
                "(f.end_nanos IS NOT NULL AND f.end_nanos > ?))",
        lowerBoundParameterCount = 2,
    ),
    SLICE(
        table = "profile_slice",
        idPrefix = "slices",
        trackKind = ProfileTrackKind.SLICES,
        exclusiveEndSql = "f.end_nanos",
        lowerBoundSql = "f.end_nanos > ?",
        lowerBoundParameterCount = 1,
    ),
}

private enum class GlobalFactKind(
    val table: String,
    val idPrefix: String,
    val trackKind: ProfileTrackKind,
) {
    COUNTER("profile_counter", "counters", ProfileTrackKind.COUNTERS),
    SCREENSHOT("profile_screenshot", "screenshots", ProfileTrackKind.SCREENSHOTS),
}

private fun ResultSet.cpuTrack(): CpuTrack =
    CpuTrack(
        key = getString(1),
        sourceId = getString(2),
        processId = getInt(3),
        threadId = getInt(4),
    )

private fun ResultSet.getNullableInt(index: Int): Int? {
    val value = getInt(index)
    return if (wasNull()) null else value
}

private fun ResultSet.getNullableLong(index: Int): Long? {
    val value = getLong(index)
    return if (wasNull()) null else value
}

private fun factKey(
    sourceId: String,
    threadRowId: Long?,
): String = "$sourceId:${threadRowId ?: "global"}"

private fun Pair<Long, Long>?.availability(): ProfileDataAvailability =
    if (this == null) ProfileDataAvailability.EMPTY else ProfileDataAvailability.AVAILABLE

private fun Long.exclusiveEnd(): Long = if (this == Long.MAX_VALUE) this else this + 1

private fun ProfileQuery.freeze(): ProfileQuery =
    copy(
        threadIds = threadIds.toSet(),
        eventTypes = eventTypes.toSet(),
    )
