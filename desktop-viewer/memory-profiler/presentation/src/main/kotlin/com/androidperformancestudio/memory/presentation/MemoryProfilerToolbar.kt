@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.UiLanguage

@Composable
public fun MemoryProfilerToolbar(
    language: UiLanguage,
    captureContent: @Composable RowScope.() -> Unit,
    analysisContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    onNavigateHome: (() -> Unit)? = null,
) {
    HeaderToolbar(
        modifier = modifier.fillMaxWidth().testTag("memory-profiler-toolbar"),
        language = language,
        onNavigateHome = onNavigateHome,
        onNavigateSettings = null,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState())
                    .testTag("memory-toolbar-scroll"),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.fillMaxHeight().testTag("memory-toolbar-capture-content"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = captureContent,
            )
            Spacer(Modifier.width(8.dp).testTag("memory-toolbar-group-gap"))
            Row(
                modifier = Modifier.fillMaxHeight().testTag("memory-toolbar-analysis-content"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = analysisContent,
            )
        }
    }
}
