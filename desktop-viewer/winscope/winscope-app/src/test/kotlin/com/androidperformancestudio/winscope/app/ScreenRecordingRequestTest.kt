package com.androidperformancestudio.winscope.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
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
    fun `floating recording resizes from all four corners while keeping the opposite corner fixed`() {
        val container = IntSize(1_000, 700)
        val minimum = IntSize(240, 160)
        val position = Offset(300f, 200f)
        val size = IntSize(360, 260)

        assertEquals(
            Offset(200f, 120f) to IntSize(460, 340),
            resizeFloatingMediaPanel(position, size, Offset(-100f, -80f), container, minimum, 12f, fromLeft = true, fromTop = true),
        )
        assertEquals(
            Offset(300f, 120f) to IntSize(460, 340),
            resizeFloatingMediaPanel(position, size, Offset(100f, -80f), container, minimum, 12f, fromLeft = false, fromTop = true),
        )
        assertEquals(
            Offset(200f, 200f) to IntSize(460, 340),
            resizeFloatingMediaPanel(position, size, Offset(-100f, 80f), container, minimum, 12f, fromLeft = true, fromTop = false),
        )
        assertEquals(
            Offset(300f, 200f) to IntSize(460, 340),
            resizeFloatingMediaPanel(position, size, Offset(100f, 80f), container, minimum, 12f, fromLeft = false, fromTop = false),
        )
    }

    @Test
    fun `floating recording resize stays within its minimum and workspace bounds`() {
        val container = IntSize(1_000, 700)
        val minimum = IntSize(240, 160)

        assertEquals(
            Offset(420f, 300f) to minimum,
            resizeFloatingMediaPanel(
                Offset(300f, 200f),
                IntSize(360, 260),
                Offset(2_000f, 2_000f),
                container,
                minimum,
                12f,
                fromLeft = true,
                fromTop = true,
            ),
        )
        assertEquals(
            Offset(12f, 12f) to IntSize(648, 448),
            resizeFloatingMediaPanel(
                Offset(300f, 200f),
                IntSize(360, 260),
                Offset(-2_000f, -2_000f),
                container,
                minimum,
                12f,
                fromLeft = true,
                fromTop = true,
            ),
        )
    }

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
