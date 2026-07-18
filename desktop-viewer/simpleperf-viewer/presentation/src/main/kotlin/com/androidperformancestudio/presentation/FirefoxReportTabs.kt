package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportTab

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxReportTabs(
    selectedTab: ReportTab,
    onSelectTab: (ReportTab) -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    Row(
        modifier =
            Modifier
                .testTag("report-tabs")
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        val currentIndex = ReportTab.entries.indexOf(selectedTab)
                        val nextIndex =
                            when (event.key) {
                                Key.DirectionLeft -> (currentIndex - 1).coerceAtLeast(0)
                                Key.DirectionRight -> (currentIndex + 1).coerceAtMost(ReportTab.entries.lastIndex)
                                else -> currentIndex
                            }
                        if (nextIndex == currentIndex) {
                            false
                        } else {
                            onSelectTab(ReportTab.entries[nextIndex])
                            true
                        }
                    }
                }.focusable(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReportTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Box(
                modifier =
                    Modifier
                        .testTag("report-tab-${tab.name}")
                        .height(28.dp)
                        .background(
                            if (selected) style.accent.copy(alpha = 0.18f) else style.toolbar,
                            RoundedCornerShape(4.dp),
                        ).selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelectTab(tab) },
                        ).semantics { this.selected = selected }
                        .semantics { contentDescription = tab.displayName() }
                        .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.displayName(),
                    color = if (selected) style.accent else style.text,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun ReportTab.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
