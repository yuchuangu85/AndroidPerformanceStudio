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
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.TimelineBucket
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.storage.TopFunctionSort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
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

@Suppress("TooManyFunctions")
class ReportController(
    private val timelineBucketCount: Int = DEFAULT_TIMELINE_BUCKET_COUNT,
    private val topFunctionLimit: Int = DEFAULT_TOP_FUNCTION_LIMIT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnosticEngine: DiagnosticEngine = DefaultDiagnosticRules.engine(),
) {
    private val mutableState = MutableStateFlow(ReportState())
    private var sessionDirectory: Path? = null
    val state: StateFlow<ReportState> = mutableState.asStateFlow()

    init {
        require(timelineBucketCount > 0) { "timelineBucketCount must be positive" }
        require(topFunctionLimit > 0) { "topFunctionLimit must be positive" }
    }

    suspend fun openSession(directory: Path) {
        sessionDirectory = directory.toAbsolutePath().normalize()
        mutableState.value = ReportState(loadState = ReportLoadState.Loading(checkNotNull(sessionDirectory)))
        reload()
    }

    fun closeSession() {
        sessionDirectory = null
        mutableState.value = ReportState()
    }

    fun showFailure(
        directory: Path,
        error: StudioError,
    ) {
        sessionDirectory = directory.toAbsolutePath().normalize()
        mutableState.value = ReportState(loadState = ReportLoadState.Failed(checkNotNull(sessionDirectory), error))
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

    private suspend fun reload() {
        val session = sessionDirectory ?: return
        val current = mutableState.value
        val result = withContext(ioDispatcher) { loadReport(session, current) }
        mutableState.value =
            when (result) {
                is ReportLoadResult.Success -> {
                    val highlighted =
                        result.report.flameGraph
                            .filter {
                                current.flameSearch.isNotBlank() &&
                                    it.symbolName.contains(current.flameSearch, ignoreCase = true)
                            }.mapTo(mutableSetOf(), ReportFlameNode::id)
                    current.copy(
                        loadState = ReportLoadState.Ready(result.report),
                        highlightedFlameNodeIds = highlighted,
                    )
                }
                is ReportLoadResult.Failure ->
                    current.copy(loadState = ReportLoadState.Failed(session, result.error))
            }
    }

    @Suppress("LongMethod")
    private fun loadReport(
        session: Path,
        state: ReportState,
    ): ReportLoadResult {
        val database = session.resolve(DATABASE_FILE)
        if (!database.isRegularFile()) {
            return ReportLoadResult.Failure(
                StudioError(ErrorCategory.IO, "REPORT_DATABASE_NOT_FOUND", "Session profile.sqlite does not exist"),
            )
        }
        return try {
            SQLiteSampleStore.open(database).use { store ->
                val forwardTree = store.callTree(state.filter, CallTreeDirection.FORWARD)
                val overview = store.overview(state.filter)
                val quality = store.dataQuality()
                val topThreads = store.threads(state.filter)
                val topFunctions =
                    store.topFunctions(
                        query = state.filter,
                        limit = topFunctionLimit,
                        search = state.topSearch,
                        sort = state.topSort,
                        descending = state.topDescending,
                    )
                ReportLoadResult.Success(
                    ReportData(
                        session = sessionSummary(session),
                        sessionOverview = store.overview(),
                        overview = overview,
                        quality = quality,
                        sessionThreads = store.threads(),
                        topThreads = topThreads,
                        topFunctions = topFunctions,
                        timeline = store.timelineBuckets(state.filter, timelineBucketCount),
                        callTree =
                            if (state.callTreeDirection == CallTreeDirection.FORWARD) {
                                forwardTree
                            } else {
                                store.callTree(state.filter, CallTreeDirection.REVERSE)
                            },
                        flameGraph = forwardTree.toFlameGraph(),
                        diagnostics =
                            diagnosticEngine.analyze(
                                AnalysisSnapshot(overview, quality, topThreads, topFunctions),
                            ),
                    ),
                )
            }
        } catch (exception: SQLException) {
            ReportLoadResult.Failure(
                StudioError(
                    ErrorCategory.DATA_VALIDATION,
                    "REPORT_QUERY_FAILED",
                    "Failed to query report database",
                    exception,
                ),
            )
        } catch (exception: IOException) {
            ReportLoadResult.Failure(
                StudioError(ErrorCategory.IO, "REPORT_SESSION_READ_FAILED", "Failed to read report session", exception),
            )
        }
    }

    companion object {
        const val WEIGHT_SEMANTICS =
            "Widths and percentages are sample/event weights; they are not exact wall-clock durations."
        private const val DATABASE_FILE = "profile.sqlite"
        private const val DEFAULT_TIMELINE_BUCKET_COUNT = 600
        private const val DEFAULT_TOP_FUNCTION_LIMIT = 200
    }
}

private sealed interface ReportLoadResult {
    data class Success(
        val report: ReportData,
    ) : ReportLoadResult

    data class Failure(
        val error: StudioError,
    ) : ReportLoadResult
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
