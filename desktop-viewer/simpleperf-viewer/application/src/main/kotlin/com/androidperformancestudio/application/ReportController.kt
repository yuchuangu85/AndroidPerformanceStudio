package com.androidperformancestudio.application

import com.androidperformancestudio.analysis.AnalysisSnapshot
import com.androidperformancestudio.analysis.DefaultDiagnosticRules
import com.androidperformancestudio.analysis.DiagnosticEngine
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallNodePath
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.TimelineBucket
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.storage.TopFunctionSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

enum class ReportTab {
    OVERVIEW,
    TIMELINE,
    TOP_FUNCTIONS,
    CALL_TREE,
    FLAME_GRAPH,
    DIAGNOSTICS,
}

data class ReportArtifact(
    val name: String,
    val path: Path,
    val exists: Boolean,
)

data class ReportSessionSummary(
    val name: String,
    val directory: Path,
    val metadata: Map<String, String>,
    val artifacts: List<ReportArtifact>,
)

data class ReportData(
    val session: ReportSessionSummary,
    val sessionOverview: ProfileOverview,
    val overview: ProfileOverview,
    val quality: DataQualitySummary,
    val sessionThreads: List<ThreadSummary>,
    val topThreads: List<ThreadSummary>,
    val topFunctions: List<TopFunction>,
    val timeline: List<TimelineBucket>,
    val callTree: List<CallTreeNode>,
    val flameGraph: FlameGraphSnapshot,
    val diagnostics: List<DiagnosticFinding>,
)

sealed interface ReportLoadState {
    data object Closed : ReportLoadState

    data class Loading(
        val sessionDirectory: Path,
    ) : ReportLoadState

    data class Ready(
        val report: ReportData,
    ) : ReportLoadState

    data class Failed(
        val sessionDirectory: Path,
        val error: StudioError,
    ) : ReportLoadState
}

data class ReportState(
    val loadState: ReportLoadState = ReportLoadState.Closed,
    val lastReadyReport: ReportData? = null,
    val selectedTab: ReportTab = ReportTab.OVERVIEW,
    val filter: ProfileQuery = ProfileQuery(),
    val topSearch: String = "",
    val topSort: TopFunctionSort = TopFunctionSort.INCLUSIVE_WEIGHT,
    val topDescending: Boolean = true,
    val callTreeSearch: String = "",
    val flameGraph: FlameGraphPanelState = FlameGraphPanelState(),
) {
    val callTreeDirection: CallStackDirection
        get() = flameGraph.query.direction
}

fun interface ReportSessionSummaryLoader {
    suspend fun load(directory: Path): ReportSessionSummary
}

@Suppress("TooManyFunctions", "LongParameterList")
class ReportController(
    timelineBucketCount: Int = DEFAULT_TIMELINE_BUCKET_COUNT,
    topFunctionLimit: Int = DEFAULT_TOP_FUNCTION_LIMIT,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnosticEngine: DiagnosticEngine = DefaultDiagnosticRules.engine(),
    scope: CoroutineScope? = null,
    workspaceController: ProfileWorkspaceController? = null,
    private val sessionSummaryLoader: ReportSessionSummaryLoader =
        ReportSessionSummaryLoader { directory -> sessionSummary(directory) },
) : Closeable {
    private val configuration = ReportConfiguration(timelineBucketCount, topFunctionLimit)
    private val ownsScope = scope == null
    private val controllerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsWorkspace = workspaceController == null
    private val workspace =
        workspaceController ?: ProfileWorkspaceController(controllerScope, sqliteProjectionLoader(ioDispatcher))
    private val mutableState = MutableStateFlow(ReportState())
    private val mutablePublicationGeneration = MutableStateFlow(ProfileGeneration(0))
    private val workspaceCollection =
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            workspace.state.collect(::publishWorkspaceState)
        }
    private var closed = false

    val state: StateFlow<ReportState> = mutableState.asStateFlow()

    suspend fun openSession(directory: Path) {
        check(!closed) { "ReportController is closed" }
        val session = directory.toAbsolutePath().normalize()
        mutableState.value = ReportState(loadState = ReportLoadState.Loading(session))
        workspace.openSession(session, mutableState.value.projectionRequest())
        awaitPublication(workspace.state.value.generation)
    }

    fun closeSession() {
        workspace.closeSession()
        mutableState.value = ReportState()
    }

    fun showFailure(
        directory: Path,
        error: StudioError,
    ) {
        workspace.closeSession()
        val session = directory.toAbsolutePath().normalize()
        mutableState.value = ReportState(loadState = ReportLoadState.Failed(session, error))
    }

    fun selectTab(tab: ReportTab) {
        mutableState.value = mutableState.value.copy(selectedTab = tab)
    }

    suspend fun updateTimeRange(
        startNanosInclusive: Long?,
        endNanosExclusive: Long?,
    ) {
        val filter =
            mutableState.value.filter.copy(
                startNanosInclusive = startNanosInclusive,
                endNanosExclusive = endNanosExclusive,
            )
        mutableState.value = mutableState.value.copy(filter = filter)
        reload()
    }

    suspend fun updateThreads(threadIds: Set<Int>) {
        mutableState.value = mutableState.value.copy(filter = mutableState.value.filter.copy(threadIds = threadIds))
        reload()
    }

    suspend fun updateEvents(eventTypes: Set<String>) {
        mutableState.value = mutableState.value.copy(filter = mutableState.value.filter.copy(eventTypes = eventTypes))
        reload()
    }

    suspend fun updateTopFunctions(
        search: String,
        sort: TopFunctionSort,
        descending: Boolean,
    ) {
        mutableState.value =
            mutableState.value.copy(topSearch = search, topSort = sort, topDescending = descending)
        reload()
    }

    suspend fun updateFlamePreviewRange(range: AnalysisTimeRange?) {
        updateCallStackQuery { copy(previewRange = range) }
    }

    suspend fun updateFlameSearch(search: String) {
        updateCallStackQuery { copy(searchText = search) }
    }

    suspend fun updateImplementationFilter(filter: ImplementationFilter) {
        updateCallStackQuery { copy(implementation = filter) }
    }

    suspend fun updateCallStackDirection(direction: CallStackDirection) {
        updateCallStackQuery { copy(direction = direction) }
    }

    suspend fun updateCallTreeDirection(direction: CallStackDirection) {
        updateCallStackDirection(direction)
    }

    suspend fun applyTransform(transform: CallStackTransform) {
        updateCallStackQuery { copy(transforms = transforms + transform) }
    }

    suspend fun removeTransform(transform: CallStackTransform) {
        updateCallStackQuery { copy(transforms = transforms.removeFirst(transform)) }
    }

    suspend fun clearTransforms() {
        updateCallStackQuery { copy(transforms = emptyList()) }
    }

    fun selectCallNode(nodeId: FlameCallNodeId?) {
        val snapshot = (mutableState.value.loadState as? ReportLoadState.Ready)?.report?.flameGraph
        val validId = nodeId?.takeIf { candidate -> snapshot?.callNodes?.contains(candidate) == true }
        mutableState.value =
            mutableState.value.copy(
                flameGraph = mutableState.value.flameGraph.copy(selectedNodeId = validId),
            )
    }

    fun focusFunction(symbolName: String) {
        mutableState.value = mutableState.value.copy(selectedTab = ReportTab.FLAME_GRAPH)
        controllerScope.launch { updateFlameSearch(symbolName) }
    }

    fun focusCallTreeFunction(symbolName: String) {
        mutableState.value =
            mutableState.value.copy(
                selectedTab = ReportTab.CALL_TREE,
                callTreeSearch = symbolName,
            )
    }

    override fun close() {
        if (closed) return
        closed = true
        workspace.closeSession()
        workspaceCollection.cancel()
        if (ownsWorkspace) workspace.close()
        if (ownsScope) controllerScope.cancel()
        mutableState.value = ReportState()
    }

    private suspend fun reload() {
        if (workspace.state.value.sessionDirectory == null) return
        workspace.updateProjection(mutableState.value.projectionRequest())
        awaitPublication(workspace.state.value.generation)
    }

    private suspend fun updateCallStackQuery(transform: CallStackAnalysisQuery.() -> CallStackAnalysisQuery) {
        val current = mutableState.value
        val nextQuery = current.flameGraph.query.transform()
        if (nextQuery == current.flameGraph.query) return
        mutableState.value = current.copy(flameGraph = current.flameGraph.copy(query = nextQuery))
        reload()
    }

    private suspend fun awaitPublication(generation: ProfileGeneration) {
        val (workspaceState, publishedGeneration) =
            combine(workspace.state, mutablePublicationGeneration) { state, published -> state to published }
                .first { (state, published) ->
                    state.generation != generation || published == generation
                }
        if (workspaceState.generation != generation || publishedGeneration != generation) {
            throw CancellationException("Report projection generation $generation was superseded")
        }
    }

    private suspend fun publishWorkspaceState(workspaceState: ProfileWorkspaceState) {
        when (val loadState = workspaceState.loadState) {
            is ProfileWorkspaceLoadState.Ready -> {
                val report =
                    try {
                        val snapshot = checkNotNull(workspaceState.snapshot)
                        snapshot.toReportData(sessionSummaryLoader.load(loadState.sessionDirectory))
                    } catch (failure: IOException) {
                        publishFailure(
                            workspaceState.generation,
                            loadState.sessionDirectory,
                            StudioError(
                                ErrorCategory.IO,
                                "REPORT_SESSION_READ_FAILED",
                                "Failed to read report session",
                                failure,
                            ),
                        )
                        return
                    }
                publishReady(workspaceState.generation, report)
            }
            is ProfileWorkspaceLoadState.Failed ->
                publishFailure(workspaceState.generation, loadState.sessionDirectory, loadState.error)
            ProfileWorkspaceLoadState.Closed,
            is ProfileWorkspaceLoadState.Loading,
            is ProfileWorkspaceLoadState.Refreshing,
            -> Unit
        }
    }

    private fun publishReady(
        generation: ProfileGeneration,
        report: ReportData,
    ) {
        var published = false
        mutableState.update { current ->
            if (workspace.state.value.generation != generation) {
                current
            } else {
                published = true
                val selected = retainSelection(current, report.flameGraph)
                current.copy(
                    loadState = ReportLoadState.Ready(report),
                    lastReadyReport = report,
                    flameGraph =
                        current.flameGraph.copy(
                            selectedNodeId = selected,
                            invalidTransforms = report.flameGraph.invalidTransforms,
                        ),
                )
            }
        }
        if (published) mutablePublicationGeneration.value = generation
    }

    private fun publishFailure(
        generation: ProfileGeneration,
        sessionDirectory: Path,
        error: StudioError,
    ) {
        var published = false
        mutableState.update { current ->
            if (workspace.state.value.generation != generation) {
                current
            } else {
                published = true
                current.copy(loadState = ReportLoadState.Failed(sessionDirectory, error))
            }
        }
        if (published) mutablePublicationGeneration.value = generation
    }

    private fun ProfileProjectionSnapshot.toReportData(session: ReportSessionSummary): ReportData =
        ReportData(
            session = session,
            sessionOverview = sessionOverview,
            overview = overview,
            quality = quality,
            sessionThreads = sessionThreads,
            topThreads = threads,
            topFunctions = topFunctions,
            timeline = timeline,
            callTree = callTree,
            flameGraph = flameGraph,
            diagnostics =
                diagnosticEngine.analyze(
                    AnalysisSnapshot(overview, quality, threads, topFunctions),
                ),
        )

    private fun ReportState.projectionRequest(): ProfileProjectionRequest =
        ProfileProjectionRequest(
            query = filter,
            callStackAnalysis = flameGraph.query,
            timelineBucketCount = configuration.timelineBucketCount,
            topFunctionLimit = configuration.topFunctionLimit,
            topSearch = topSearch,
            topSort = topSort,
            topDescending = topDescending,
        )

    companion object {
        const val WEIGHT_SEMANTICS =
            "Widths and percentages are sample/event weights; they are not exact wall-clock durations."
        private const val DEFAULT_TIMELINE_BUCKET_COUNT = 600
        private const val DEFAULT_TOP_FUNCTION_LIMIT = 200
    }
}

private fun sessionSummary(directory: Path): ReportSessionSummary =
    ReportSessionSummary(
        name = directory.fileName?.toString().orEmpty(),
        directory = directory,
        metadata = readMetadata(directory),
        artifacts =
            REPORT_ARTIFACTS.map { name ->
                val path = directory.resolve(name)
                ReportArtifact(name, path, Files.exists(path))
            },
    )

private data class ReportConfiguration(
    val timelineBucketCount: Int,
    val topFunctionLimit: Int,
) {
    init {
        require(timelineBucketCount > 0) { "timelineBucketCount must be positive" }
        require(topFunctionLimit > 0) { "topFunctionLimit must be positive" }
    }
}

private fun readMetadata(directory: Path): Map<String, String> {
    val metadata = linkedMapOf<String, String>()
    listOf("session.properties", "import.properties").forEach { name ->
        val path = directory.resolve(name)
        if (path.isRegularFile()) Files.readAllLines(path).forEach(metadata::addPropertyLine)
    }
    return metadata
}

private fun MutableMap<String, String>.addPropertyLine(line: String) {
    val separator = line.indexOf('=')
    if (separator > 0) this[line.substring(0, separator)] = line.substring(separator + 1)
}

private fun retainSelection(
    state: ReportState,
    next: FlameGraphSnapshot,
): FlameCallNodeId? {
    val selected = state.flameGraph.selectedNodeId
    return when {
        selected == null -> null
        next.callNodes.contains(selected) -> selected
        else ->
            state.lastReadyReport
                ?.flameGraph
                ?.callNodes
                ?.pathFor(selected)
                ?.nearestVisibleAncestor(next.callNodes)
    }
}

private fun CallNodePath.nearestVisibleAncestor(next: CallNodeTable): FlameCallNodeId? =
    functions.indices.reversed().firstNotNullOfOrNull { lastIndex ->
        next.findByPath(CallNodePath(functions.take(lastIndex + 1)))
    }

private fun CallNodeTable.contains(nodeId: FlameCallNodeId): Boolean = ids.any { it == nodeId.value }

private fun CallNodeTable.pathFor(nodeId: FlameCallNodeId): CallNodePath? {
    val nodeIds = ids
    var index = nodeIds.indexOf(nodeId.value)
    if (index < 0) return null
    val parents = parentIndexes
    val nodeFrameIds = frameIds
    val reversed = ArrayList<FlameFunctionId>()
    while (index >= 0) {
        reversed += framesById.getValue(nodeFrameIds[index]).functionId
        index = parents[index]
    }
    reversed.reverse()
    return CallNodePath(reversed)
}

private fun <T> List<T>.removeFirst(item: T): List<T> {
    val index = indexOf(item)
    return if (index < 0) this else filterIndexed { candidateIndex, _ -> candidateIndex != index }
}

private val REPORT_ARTIFACTS =
    listOf(
        "perf.data",
        "simpleperf.protobuf",
        "mapping.txt",
        "symbols",
        "capture-command.txt",
        "session.properties",
        "import.properties",
    )
