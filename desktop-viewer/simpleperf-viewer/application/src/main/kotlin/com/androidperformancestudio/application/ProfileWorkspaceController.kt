package com.androidperformancestudio.application

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicLong

class ProfileWorkspaceController(
    scope: CoroutineScope,
    loader: ProfileProjectionLoader = sqliteProjectionLoader(),
    private val migrator: ProfileSessionMigrator = ProfileSessionMigrator(),
) : Closeable {
    private val mutableState = MutableStateFlow(ProfileWorkspaceState())
    private val coordinator = ProfileQueryCoordinator(scope, loader)
    private val collectionJobs: List<Job>
    private var closed = false
    private var databaseLocation: Path? = null
    private val lastGeneration = AtomicLong()

    val state: StateFlow<ProfileWorkspaceState> = mutableState.asStateFlow()

    init {
        collectionJobs =
            listOf(
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.results.collect(::publishReady)
                },
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.failures.collect(::publishFailure)
                },
            )
    }

    fun openSession(directory: Path) {
        openSession(directory, ProfileProjectionRequest())
    }

    @Synchronized
    fun openSession(
        directory: Path,
        request: ProfileProjectionRequest,
    ) {
        check(!closed) { "ProfileWorkspaceController is closed" }
        val session = directory.toAbsolutePath().normalize()
        val prepared = migrator.prepare(session)
        databaseLocation = prepared.database
        val query = request.query.freeze()
        val generation = nextGeneration()
        mutableState.value =
            ProfileWorkspaceState(
                generation = generation,
                sessionDirectory = session,
                sessionMode = prepared.mode,
                query = query,
                loadState = ProfileWorkspaceLoadState.Loading(session),
            )
        coordinator.submit(prepared.database, generation, request.copy(query = query))
    }

    fun updateQuery(query: ProfileQuery) {
        updateProjection(ProfileProjectionRequest(query = query))
    }

    @Synchronized
    fun updateProjection(request: ProfileProjectionRequest) {
        check(!closed) { "ProfileWorkspaceController is closed" }
        checkNotNull(mutableState.value.sessionDirectory) { "No profile session is open" }
        val database = checkNotNull(databaseLocation) { "No profile database is open" }
        val frozenRequest = request.copy(query = request.query.freeze())
        val next = mutableState.value.request(frozenRequest.query).copy(generation = nextGeneration())
        mutableState.value = next
        coordinator.submit(database, next.generation, frozenRequest)
    }

    @Synchronized
    fun closeSession() {
        coordinator.cancel()
        databaseLocation = null
        mutableState.value = ProfileWorkspaceState()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        coordinator.close()
        collectionJobs.forEach(Job::cancel)
        databaseLocation = null
        mutableState.value = ProfileWorkspaceState()
    }

    private fun publishReady(result: GeneratedProjection) {
        mutableState.update { current ->
            val session = current.sessionDirectory
            if (result.generation != current.generation || session == null) {
                current
            } else {
                current.copy(
                    query = result.snapshot.query,
                    snapshot = result.snapshot,
                    loadState = ProfileWorkspaceLoadState.Ready(session),
                )
            }
        }
    }

    private fun publishFailure(failure: GeneratedProjectionFailure) {
        mutableState.update { current ->
            val session = current.sessionDirectory
            if (failure.generation != current.generation || session == null) {
                current
            } else {
                current.copy(
                    loadState =
                        ProfileWorkspaceLoadState.Failed(
                            session,
                            failure.cause.toStudioError(),
                        ),
                )
            }
        }
    }

    private fun nextGeneration(): ProfileGeneration = ProfileGeneration(lastGeneration.incrementAndGet())
}

private fun Throwable.toStudioError(): StudioError =
    when (this) {
        is ProfileProjectionLoadException -> error
        is SQLException ->
            StudioError(
                ErrorCategory.DATA_VALIDATION,
                "REPORT_QUERY_FAILED",
                "Failed to query report database",
                this,
            )
        is IOException ->
            StudioError(
                ErrorCategory.IO,
                "REPORT_SESSION_READ_FAILED",
                "Failed to read report session",
                this,
            )
        else ->
            StudioError(
                ErrorCategory.UNKNOWN,
                "REPORT_QUERY_FAILED",
                "Failed to load report projection",
                this,
            )
    }

private fun ProfileQuery.freeze(): ProfileQuery =
    copy(
        threadIds = threadIds.toSet(),
        eventTypes = eventTypes.toSet(),
    )
