package com.androidperformancestudio.presentation

import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphFrameDetails
import com.androidperformancestudio.application.ProfileGeneration
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlameGraphDetailsPresenterTest {
    @Test
    fun `closed loading source disassembly and fallback states produce truthful panel content`() {
        assertNull(FlameGraphDetailsPresenter.content(FlameGraphDetailsState.Closed))
        assertEquals(
            "Loading frame details…",
            FlameGraphDetailsPresenter
                .content(FlameGraphDetailsState.Loading(FlameCallNodeId(7), ProfileGeneration(2)))
                ?.title,
        )

        val source =
            FlameGraphDetailsPresenter.content(
                FlameGraphDetailsState.Ready(
                    FlameGraphFrameDetails.Source(Path.of("Render.cpp"), 2, 3, listOf("one", "two")),
                ),
            )
        assertEquals("Source · Render.cpp:2:3", source?.title)
        assertEquals(1, source?.selectedLineIndex)

        val disassembly =
            FlameGraphDetailsPresenter.content(
                FlameGraphDetailsState.Ready(
                    FlameGraphFrameDetails.Disassembly(Path.of("libui.so"), 0x100, listOf("mov x0, x1")),
                ),
            )
        assertEquals("Disassembly · libui.so @ 0x100", disassembly?.title)
        assertTrue(disassembly?.monospace == true)

        val fallback =
            FlameGraphDetailsPresenter.content(
                FlameGraphDetailsState.Ready(
                    FlameGraphFrameDetails.SymbolFallback(
                        function = "renderFrame",
                        resource = "/system/libui.so",
                        address = 0x100,
                        libraryOffset = 0x80,
                        buildId = "aa11",
                        reason = "Binary unavailable",
                    ),
                ),
            )
        assertEquals("Symbol details", fallback?.title)
        assertTrue(fallback?.lines.orEmpty().any { line -> line.contains("Binary unavailable") })
    }
}
