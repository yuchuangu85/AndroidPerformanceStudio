@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.UiLanguage

private val NAME_WARNING_WIDTH = 22.dp

@Composable
internal fun memoryTableViewportRight(
    viewportWidth: Dp,
    scroll: ScrollState,
): Dp = viewportWidth + with(LocalDensity.current) { scroll.value.toDp() }

@Composable
@Suppress("LongParameterList")
internal fun MemoryTableNameCell(
    warning: Boolean,
    viewportRight: Dp,
    warningTag: String,
    language: UiLanguage,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val visibleRight = minOf(maxWidth, viewportRight).coerceAtLeast(0.dp)
        val warningWidth = if (warning) NAME_WARNING_WIDTH else 0.dp
        Row(
            Modifier.width(visibleRight).fillMaxHeight().padding(end = warningWidth),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (warning) {
            // Keep the marker inside its name column, including a partially visible wide column.
            MemoryLeakWarningIcon(
                language,
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = visibleRight - maxWidth)
                    .padding(horizontal = 4.dp)
                    .testTag(warningTag),
            )
        }
    }
}
