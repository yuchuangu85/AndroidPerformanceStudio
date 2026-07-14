package com.androidperformancestudio.application

import com.androidperformancestudio.analysis.AnalysisSnapshot
import com.androidperformancestudio.analysis.DefaultDiagnosticRules
import com.androidperformancestudio.analysis.DiagnosticEngine
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.storage.CallTreeDirection
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

data class ReportFlameNode(
    val id: Long,
    val parentId: Long?,
    val depth: Int,
    val symbolName: String,
    val filePath: String,
    val path: List<String>,
    val startWeight: Long,
    val endWeightExclusive: Long,
    val inclusiveWeight: Long,
    val exclusiveWeight: Long,
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
    val flameGraph: List<ReportFlameNode>,
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
    val callTreeDirection: CallTreeDirection = CallTreeDirection.FORWARD,
    val callTreeSearch: String = "",
    val flameSearch: String = "",
    val highlightedFlameNodeIds: Set<Long> = emptySet(),
)

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

    suspend fun updateCallTreeDirection(direction: CallTreeDirection) {
        mutableState.value = mutableState.value.copy(callTreeDirection = direction)
        reload()
    }

    fun focusFunction(symbolName: String) {
        val report = (mutableState.value.loadState as? ReportLoadState.Ready)?.report ?: return
        val matching =
            report.flameGraph
                .filter { it.symbolName.contains(symbolName, ignoreCase = true) }
                .mapTo(mutableSetOf(), ReportFlameNode::id)
        mutableState.value =
            mutableState.value.copy(
                selectedTab = ReportTab.FLAME_GRAPH,
                flameSearch = symbolName,
                highlightedFlameNodeIds = matching,
            )
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
                val highlighted =
                    report.flameGraph
                        .filter {
                            current.flameSearch.isNotBlank() &&
                                it.symbolName.contains(current.flameSearch, ignoreCase = true)
                        }.mapTo(mutableSetOf(), ReportFlameNode::id)
                current.copy(
                    loadState = ReportLoadState.Ready(report),
                    lastReadyReport = report,
                    highlightedFlameNodeIds = highlighted,
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
            flameGraph = forwardCallTree.toFlameGraph(),
            diagnostics =
                diagnosticEngine.analyze(
                    AnalysisSnapshot(overview, quality, threads, topFunctions),
                ),
        )

    private fun ReportState.projectionRequest(): ProfileProjectionRequest =
        ProfileProjectionRequest(
            query = filter,
            timelineBucketCount = configuration.timelineBucketCount,
            topFunctionLimit = configuration.topFunctionLimit,
            topSearch = topSearch,
            topSort = topSort,
            topDescending = topDescending,
            callTreeDirection = callTreeDirection,
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

private fun List<CallTreeNode>.toFlameGraph(): List<ReportFlameNode> {
    val children = groupBy(CallTreeNode::parentId)
    val result = mutableListOf<ReportFlameNode>()
    var rootStart = 0L
    children[null].orEmpty().sortedByDescending(CallTreeNode::inclusiveWeight).forEach { root ->
        addFlameNode(root, rootStart, emptyList(), children, result)
        rootStart += root.inclusiveWeight
    }
    return result
}

private fun addFlameNode(
    node: CallTreeNode,
    startWeight: Long,
    parentPath: List<String>,
    children: Map<Long?, List<CallTreeNode>>,
    result: MutableList<ReportFlameNode>,
) {
    val path = parentPath + node.symbolName
    result +=
        ReportFlameNode(
            id = node.id,
            parentId = node.parentId,
            depth = node.depth,
            symbolName = node.symbolName,
            filePath = node.filePath,
            path = path,
            startWeight = startWeight,
            endWeightExclusive = startWeight + node.inclusiveWeight,
            inclusiveWeight = node.inclusiveWeight,
            exclusiveWeight = node.exclusiveWeight,
        )
    var childStart = startWeight
    children[node.id].orEmpty().sortedByDescending(CallTreeNode::inclusiveWeight).forEach { child ->
        addFlameNode(child, childStart, path, children, result)
        childStart += child.inclusiveWeight
    }
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
