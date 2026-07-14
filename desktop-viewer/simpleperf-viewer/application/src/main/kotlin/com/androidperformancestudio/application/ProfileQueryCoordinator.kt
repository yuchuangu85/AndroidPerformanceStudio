package com.androidperformancestudio.application

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.isRegularFile

fun interface ProfileProjectionLoader {
    suspend fun load(
        sessionDirectory: Path,
        query: ProfileQuery,
    ): ProfileProjectionSnapshot

    suspend fun load(
        sessionDirectory: Path,
        request: ProfileProjectionRequest,
    ): ProfileProjectionSnapshot = load(sessionDirectory, request.query)
}

data class GeneratedProjection(
    val generation: ProfileGeneration,
    val snapshot: ProfileProjectionSnapshot,
)

data class GeneratedProjectionFailure(
    val generation: ProfileGeneration,
    val cause: Throwable,
)

internal class ProfileProjectionLoadException(
    val error: StudioError,
) : IOException(error.message, error.cause)

@Suppress("TooGenericExceptionCaught")
class ProfileQueryCoordinator(
    private val scope: CoroutineScope,
    private val loader: ProfileProjectionLoader,
) : Closeable {
    private val lock = Any()
    private val mutableResults =
        MutableSharedFlow<GeneratedProjection>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val mutableFailures =
        MutableSharedFlow<GeneratedProjectionFailure>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private var activeJob: Job? = null
    private var submissionId: Long = 0
    private var closed: Boolean = false

    val results: SharedFlow<GeneratedProjection> = mutableResults.asSharedFlow()
    val failures: SharedFlow<GeneratedProjectionFailure> = mutableFailures.asSharedFlow()

    fun submit(
        sessionDirectory: Path,
        generation: ProfileGeneration,
        query: ProfileQuery,
    ) = submit(sessionDirectory, generation, ProfileProjectionRequest(query = query))

    fun submit(
        sessionDirectory: Path,
        generation: ProfileGeneration,
        request: ProfileProjectionRequest,
    ) {
        val frozenRequest = request.freeze()
        val previousJob: Job?
        val nextJob: Job
        synchronized(lock) {
            check(!closed) { "ProfileQueryCoordinator is closed" }
            val currentSubmission = ++submissionId
            previousJob = activeJob
            nextJob =
                scope.launch(start = CoroutineStart.LAZY) {
                    val job = checkNotNull(currentCoroutineContext()[Job])
                    try {
                        val snapshot = loader.load(sessionDirectory, frozenRequest)
                        job.ensureActive()
                        synchronized(lock) {
                            if (!closed && currentSubmission == submissionId && job.isActive) {
                                check(mutableResults.tryEmit(GeneratedProjection(generation, snapshot)))
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        synchronized(lock) {
                            if (!closed && currentSubmission == submissionId && job.isActive) {
                                check(mutableFailures.tryEmit(GeneratedProjectionFailure(generation, failure)))
                            }
                        }
                    }
                }
            activeJob = nextJob
        }
        previousJob?.cancel()
        nextJob.start()
    }

    fun cancel() {
        val cancelledJob =
            synchronized(lock) {
                submissionId++
                activeJob.also { activeJob = null }
            }
        cancelledJob?.cancel()
    }

    override fun close() {
        val cancelledJob =
            synchronized(lock) {
                if (closed) return
                closed = true
                submissionId++
                activeJob.also { activeJob = null }
            }
        cancelledJob?.cancel()
    }
}

fun sqliteProjectionLoader(ioDispatcher: CoroutineDispatcher = Dispatchers.IO): ProfileProjectionLoader =
    object : ProfileProjectionLoader {
        override suspend fun load(
            sessionDirectory: Path,
            query: ProfileQuery,
        ): ProfileProjectionSnapshot = load(sessionDirectory, ProfileProjectionRequest(query = query))

        override suspend fun load(
            sessionDirectory: Path,
            request: ProfileProjectionRequest,
        ): ProfileProjectionSnapshot =
            withContext(ioDispatcher) {
                val database = sessionDirectory.resolve("profile.sqlite")
                if (!database.isRegularFile()) {
                    throw ProfileProjectionLoadException(
                        StudioError(
                            ErrorCategory.IO,
                            "REPORT_DATABASE_NOT_FOUND",
                            "Session profile.sqlite does not exist",
                        ),
                    )
                }
                try {
                    SQLiteSampleStore.open(database).use { store -> store.projectCore(request) }
                } catch (failure: SQLException) {
                    throw ProfileProjectionLoadException(
                        StudioError(
                            ErrorCategory.DATA_VALIDATION,
                            "REPORT_QUERY_FAILED",
                            "Failed to query report database",
                            failure,
                        ),
                    )
                } catch (failure: ProfileProjectionLoadException) {
                    throw failure
                } catch (failure: IOException) {
                    throw ProfileProjectionLoadException(
                        StudioError(
                            ErrorCategory.IO,
                            "REPORT_SESSION_READ_FAILED",
                            "Failed to read report session",
                            failure,
                        ),
                    )
                }
            }
    }

private fun ProfileQuery.freeze(): ProfileQuery =
    copy(
        threadIds = threadIds.toSet(),
        eventTypes = eventTypes.toSet(),
    )

private fun ProfileProjectionRequest.freeze(): ProfileProjectionRequest = copy(query = query.freeze())
