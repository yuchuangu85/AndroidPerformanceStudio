package com.androidperformancestudio.application

import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
import java.nio.file.Path

fun interface ProfileProjectionLoader {
    suspend fun load(
        sessionDirectory: Path,
        query: ProfileQuery,
    ): ProfileProjectionSnapshot
}

data class GeneratedProjection(
    val generation: ProfileGeneration,
    val snapshot: ProfileProjectionSnapshot,
)

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
    private var activeJob: Job? = null
    private var submissionId: Long = 0
    private var closed: Boolean = false

    val results: SharedFlow<GeneratedProjection> = mutableResults.asSharedFlow()

    fun submit(
        sessionDirectory: Path,
        generation: ProfileGeneration,
        query: ProfileQuery,
    ) {
        val frozenQuery = query.freeze()
        synchronized(lock) {
            check(!closed) { "ProfileQueryCoordinator is closed" }
            val currentSubmission = ++submissionId
            activeJob?.cancel()
            activeJob =
                scope.launch {
                    val snapshot = loader.load(sessionDirectory, frozenQuery)
                    val job = checkNotNull(currentCoroutineContext()[Job])
                    job.ensureActive()
                    synchronized(lock) {
                        if (!closed && currentSubmission == submissionId && job.isActive) {
                            check(mutableResults.tryEmit(GeneratedProjection(generation, snapshot)))
                        }
                    }
                }
        }
    }

    fun cancel() {
        synchronized(lock) {
            submissionId++
            activeJob?.cancel()
            activeJob = null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            submissionId++
            activeJob?.cancel()
            activeJob = null
        }
    }
}

fun sqliteProjectionLoader(ioDispatcher: CoroutineDispatcher = Dispatchers.IO): ProfileProjectionLoader =
    ProfileProjectionLoader { sessionDirectory, query ->
        withContext(ioDispatcher) {
            SQLiteSampleStore.open(sessionDirectory.resolve("profile.sqlite")).use { store ->
                store.projectCore(query)
            }
        }
    }

private fun ProfileQuery.freeze(): ProfileQuery =
    copy(
        threadIds = threadIds.toSet(),
        eventTypes = eventTypes.toSet(),
    )
