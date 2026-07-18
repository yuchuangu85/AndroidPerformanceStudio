package com.androidperformancestudio.presentation

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportTab
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirefoxReportResponsiveTest {
    @Test
    fun `narrow report stacks details below content without hiding tabs or filters`() =
        runDesktopComposeUiTest(width = 650, height = 800) {
            var density = 1f
            setContent {
                density = LocalDensity.current.density
                ReportPage(sampleReportState(), goldenActions())
            }

            ReportTab.entries.forEach { onNodeWithTag("report-tab-${it.name}").assertExists() }
            val content = onNodeWithTag("report-content").fetchSemanticsNode().boundsInRoot
            val details = onNodeWithTag("report-details").fetchSemanticsNode().boundsInRoot
            val filter = onNodeWithContentDescription("Filter Stacks").fetchSemanticsNode().boundsInRoot

            assertTrue(content.bottom <= details.top + 1f, "content=$content details=$details")
            assertTrue(filter.width >= 140f * density, "filter width=${filter.width}, density=$density")
        }
}
