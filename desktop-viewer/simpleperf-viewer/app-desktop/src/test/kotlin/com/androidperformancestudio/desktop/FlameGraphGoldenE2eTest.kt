package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphFrameDetails
import com.androidperformancestudio.application.FlameGraphFrameDetailsProvider
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.fixtures.FirefoxFlameGraphFixtures
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlameGraphGoldenE2eTest {
    @Test
    fun `fixture opens selects frame and publishes source-less details fallback`() =
        runTest {
            val session = FirefoxFlameGraphFixtures.writeCompatibilitySession(Files.createTempDirectory("firefox-e2e-"))
            val controller =
                ReportController(
                    scope = backgroundScope,
                    detailsProvider =
                        FlameGraphFrameDetailsProvider { request ->
                            FlameGraphFrameDetails.SymbolFallback(
                                function = request.function,
                                resource = request.resource,
                                address = request.address,
                                libraryOffset = request.libraryOffset,
                                buildId = request.buildId,
                                reason = "source-less fixture fallback",
                            )
                        },
                )

            controller.openSession(session)
            val ready = assertIs<ReportLoadState.Ready>(controller.state.value.loadState)
            assertTrue(ready.report.flameGraph.totalWeight > 0)
            val firstNode =
                FlameCallNodeId(
                    ready.report.flameGraph.callNodes.ids
                        .first(),
                )

            controller.openFrameDetails(firstNode)
            runCurrent()

            val details = assertIs<FlameGraphDetailsState.Ready>(controller.state.value.flameGraph.details).details
            assertIs<FlameGraphFrameDetails.SymbolFallback>(details)
            assertEquals(FlameGraphDetailsState.Ready(details), controller.state.value.flameGraph.details)
        }
}
