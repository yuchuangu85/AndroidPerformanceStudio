package com.androidperformancestudio.winscope.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenRecordingRequestTest {
    @Test
    fun `timestamp requests continue after recomposition`() =
        runBlocking {
            val timestamp = mutableStateOf(1L)
            val observed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    screenRecordingTimestampRequests(timestamp).take(2).toList()
                }

            yield()
            timestamp.value = 2L
            Snapshot.sendApplyNotifications()

            assertEquals(listOf(1L, 2L), withTimeout(1_000L) { observed.await() })
        }

    @Test
    fun `slow decoding skips obsolete seeks and reaches the latest timestamp`() =
        runBlocking {
            val requests = MutableSharedFlow<Long>(extraBufferCapacity = 3)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val latestFinished = CompletableDeferred<Unit>()
            val decoded = mutableListOf<Long>()
            val worker =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    requests.collectScreenRecordingRequests { timestamp ->
                        decoded += timestamp
                        if (timestamp == 1L) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        if (timestamp == 3L) latestFinished.complete(Unit)
                    }
                }

            yield()
            requests.emit(1L)
            withTimeout(1_000L) { firstStarted.await() }
            requests.emit(2L)
            requests.emit(3L)
            releaseFirst.complete(Unit)
            withTimeout(1_000L) { latestFinished.await() }

            assertEquals(listOf(1L, 3L), decoded)
            worker.cancelAndJoin()
        }
}
