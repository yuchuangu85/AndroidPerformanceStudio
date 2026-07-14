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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.sql.SQLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface ProfileProjectionLoader {
    suspend fun load(
        session: PreparedProfileSession,
        query: ProfileQuery,
    ): ProfileProjectionSnapshot

    suspend fun load(
        session: PreparedProfileSession,
        request: ProfileProjectionRequest,
    ): ProfileProjectionSnapshot = load(session, request.query)
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

internal interface InterruptibleProjectionStore : Closeable {
    fun projectCore(request: ProfileProjectionRequest): ProfileProjectionSnapshot

    fun interrupt()
}

internal fun interface InterruptibleProjectionStoreOpener {
    fun open(session: PreparedProfileSession): InterruptibleProjectionStore
}

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
        session: PreparedProfileSession,
        generation: ProfileGeneration,
        query: ProfileQuery,
    ) = submit(session, generation, ProfileProjectionRequest(query = query))

    fun submit(
        session: PreparedProfileSession,
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
                        val snapshot = loader.load(session, frozenRequest)
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
    sqliteProjectionLoader(ioDispatcher, DEFAULT_PROJECTION_STORE_OPENER)

internal fun sqliteProjectionLoader(
    ioDispatcher: CoroutineDispatcher,
    storeOpener: InterruptibleProjectionStoreOpener,
): ProfileProjectionLoader =
    object : ProfileProjectionLoader {
        override suspend fun load(
            session: PreparedProfileSession,
            query: ProfileQuery,
        ): ProfileProjectionSnapshot = load(session, ProfileProjectionRequest(query = query))

        override suspend fun load(
            session: PreparedProfileSession,
            request: ProfileProjectionRequest,
        ): ProfileProjectionSnapshot =
            withContext(ioDispatcher) {
                try {
                    loadInterruptibly(session, request, storeOpener)
                } catch (cancelled: CancellationException) {
                    throw cancelled
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

@Suppress("TooGenericExceptionCaught")
private suspend fun loadInterruptibly(
    session: PreparedProfileSession,
    request: ProfileProjectionRequest,
    storeOpener: InterruptibleProjectionStoreOpener,
): ProfileProjectionSnapshot =
    suspendCancellableCoroutine { continuation ->
        val cancellation = ProjectionCancellation()
        continuation.invokeOnCancellation { cancellation.cancel() }
        var store: InterruptibleProjectionStore? = null
        var snapshot: ProfileProjectionSnapshot? = null
        var failure: Throwable? = null
        try {
            if (continuation.isActive) {
                store = storeOpener.open(session)
                if (cancellation.install(store) && continuation.isActive) {
                    snapshot = store.projectCore(request)
                }
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            cancellation.remove(store)
            try {
                store?.close()
            } catch (closeFailure: Throwable) {
                failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
            }
        }
        if (continuation.isActive) {
            failure?.let(continuation::resumeWithException)
                ?: continuation.resume(checkNotNull(snapshot) { "SQLite projection completed without a result" })
        }
    }

private class ProjectionCancellation {
    private val lock = Any()
    private var cancelled: Boolean = false
    private var store: InterruptibleProjectionStore? = null

    fun install(openedStore: InterruptibleProjectionStore): Boolean =
        synchronized(lock) {
            if (cancelled) {
                openedStore.interruptQuietly()
                false
            } else {
                store = openedStore
                true
            }
        }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            store?.interruptQuietly()
        }
    }

    fun remove(openedStore: InterruptibleProjectionStore?) {
        synchronized(lock) {
            if (store === openedStore) store = null
        }
    }
}

private fun InterruptibleProjectionStore.interruptQuietly() {
    try {
        interrupt()
    } catch (_: Exception) {
        // Cancellation must still let the query unwind and close even if SQLite is already closed.
    }
}

private val DEFAULT_PROJECTION_STORE_OPENER =
    InterruptibleProjectionStoreOpener { session ->
        val database = session.database
        if (Files.isSymbolicLink(database) || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            throw ProfileProjectionLoadException(
                StudioError(
                    ErrorCategory.IO,
                    "REPORT_DATABASE_NOT_FOUND",
                    "Session profile.sqlite does not exist",
                ),
            )
        }
        val store =
            when (session.mode) {
                ProfileSessionMode.READ_WRITE_V2 -> {
                    check(session.schemaVersion == 2) { "Writable session is not prepared as schema v2" }
                    SQLiteSampleStore.openV2(database)
                }
                ProfileSessionMode.LEGACY_READ_ONLY -> {
                    val expectedVersion = checkNotNull(session.schemaVersion) { "Unknown read-only schema" }
                    SQLiteSampleStore.openReadOnlyExpected(database, expectedVersion)
                }
            }
        object : InterruptibleProjectionStore {
            override fun projectCore(request: ProfileProjectionRequest) = store.projectCore(request)

            override fun interrupt() = store.interrupt()

            override fun close() = store.close()
        }
    }

private fun ProfileQuery.freeze(): ProfileQuery =
    copy(
        threadIds = threadIds.toSet(),
        eventTypes = eventTypes.toSet(),
    )

private fun ProfileProjectionRequest.freeze(): ProfileProjectionRequest = copy(query = query.freeze())
