package com.androidperformancestudio.application

import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProfileWorkspaceStateTest {
    @Test
    fun `new workspace is closed at generation zero`() {
        val state = ProfileWorkspaceState()

        assertEquals(ProfileGeneration(0), state.generation)
        assertNull(state.sessionDirectory)
        assertEquals(ProfileQuery(), state.query)
        assertNull(state.snapshot)
        assertIs<ProfileWorkspaceLoadState.Closed>(state.loadState)
    }

    @Test
    fun `query changes advance generation and retain the last ready snapshot`() {
        val ready = workspaceStateFixture()
        val nextQuery = ProfileQuery(threadIds = setOf(42))

        val next = ready.request(nextQuery)

        assertEquals(ProfileGeneration(ready.generation.value + 1), next.generation)
        assertEquals(ready.sessionDirectory, next.sessionDirectory)
        assertEquals(nextQuery, next.query)
        assertEquals(ready.snapshot, next.snapshot)
        assertEquals(ProfileWorkspaceLoadState.Refreshing(ready.sessionDirectory!!), next.loadState)
        assertIs<ProfileWorkspaceLoadState.Ready>(ready.loadState)
    }

    @Test
    fun `query request without an open session is rejected`() {
        val closed = ProfileWorkspaceState()

        assertFailsWith<IllegalStateException> {
            closed.request(ProfileQuery(threadIds = setOf(42)))
        }

        assertEquals(ProfileGeneration(0), closed.generation)
        assertIs<ProfileWorkspaceLoadState.Closed>(closed.loadState)
    }

    private fun workspaceStateFixture(): ProfileWorkspaceState {
        val sessionDirectory = Path.of("session")
        val query = ProfileQuery()
        return ProfileWorkspaceState(
            generation = ProfileGeneration(7),
            sessionDirectory = sessionDirectory,
            query = query,
            snapshot = snapshotFixture(query),
            loadState = ProfileWorkspaceLoadState.Ready(sessionDirectory),
        )
    }

    private fun snapshotFixture(query: ProfileQuery): ProfileProjectionSnapshot =
        ProfileProjectionSnapshot(
            query = query,
            overview =
                ProfileOverview(
                    startNanos = null,
                    endNanosInclusive = null,
                    sampleCount = 0,
                    totalEventWeight = 0,
                    processCount = 0,
                    threadCount = 0,
                    eventTypes = emptyList(),
                ),
            quality =
                DataQualitySummary(
                    sampleCount = 0,
                    reportedSampleCount = 0,
                    lostSampleCount = 0,
                    unwindErrorSamples = 0,
                    unknownSymbolSamples = 0,
                    emptyStackSamples = 0,
                    unknownRecords = 0,
                    unwindErrors = emptyList(),
                ),
            tracks = emptyList(),
            threads = emptyList(),
            timeline = emptyList(),
            topFunctions = emptyList(),
            flameGraph = emptyFlameGraph(),
            callTree = emptyList(),
            stackChart = emptyStackChart(),
            markers = emptyMarkers(),
        )
}
