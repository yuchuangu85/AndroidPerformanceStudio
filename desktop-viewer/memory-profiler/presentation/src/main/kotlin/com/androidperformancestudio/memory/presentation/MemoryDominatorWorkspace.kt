@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.dominator_tree
import com.androidperformancestudio.memory.presentation.generated.resources.instance_details
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioTokens
import java.awt.Cursor

private val FILTERS_WIDTH = 190.dp
private val MINIMUM_TREE_WIDTH = 260.dp
private val MINIMUM_DETAILS_WIDTH = 260.dp
private val SPLITTER_WIDTH = 7.dp
private const val INITIAL_TREE_FRACTION = 0.62f
private val KEYBOARD_STEP = 16.dp

@Composable
internal fun MemoryDominatorWorkspace(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val scroll = rememberScrollState()
        val minimumWidth =
            FILTERS_WIDTH + StudioTokens.cardGap + MINIMUM_TREE_WIDTH + SPLITTER_WIDTH + MINIMUM_DETAILS_WIDTH
        val contentWidth = maxOf(maxWidth, minimumWidth)
        val scrollbarInset = if (contentWidth > maxWidth) LocalScrollbarStyle.current.thickness else 0.dp
        Row(
            Modifier
                .fillMaxSize()
                .padding(bottom = scrollbarInset)
                .horizontalScroll(scroll)
                .requiredWidth(contentWidth),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            StudioCard(
                Modifier.width(FILTERS_WIDTH).fillMaxHeight().testTag("memory-filters-card"),
                contentPadding = 0.dp,
                contentSpacing = 0.dp,
            ) {
                MemoryFiltersPane(state, actions, language, Modifier.fillMaxSize().testTag("memory-workspace-filters"))
            }
            MemoryDominatorAnalysisCard(state, actions, language, Modifier.weight(1f).fillMaxHeight(), content)
        }
        if (scrollbarInset > 0.dp) {
            HorizontalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier.align(Alignment.BottomStart).fillMaxWidth().testTag("memory-analysis-horizontal-scrollbar"),
            )
        }
    }
}

@Composable
private fun MemoryDominatorAnalysisCard(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val treeFraction = remember { mutableStateOf(INITIAL_TREE_FRACTION) }
    StudioCard(
        modifier.testTag("memory-dominator-analysis-card"),
        contentPadding = 0.dp,
        contentSpacing = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneWidth = (maxWidth - SPLITTER_WIDTH).coerceAtLeast(0.dp)
            val minimumFraction = if (paneWidth > 0.dp) minOf(MINIMUM_TREE_WIDTH, paneWidth / 2) / paneWidth else 0f
            val maximumFraction =
                if (paneWidth > 0.dp) 1f - minOf(MINIMUM_DETAILS_WIDTH, paneWidth / 2) / paneWidth else 1f
            val range = minimumFraction..maximumFraction
            val fraction = treeFraction.value.coerceIn(range)
            Row(Modifier.fillMaxSize().testTag("memory-dominator-split-content")) {
                Box(Modifier.width(paneWidth * fraction).fillMaxHeight().testTag("memory-dominator-tree-pane")) {
                    content(Modifier.fillMaxSize())
                }
                DominatorDetailsDivider(treeFraction, paneWidth, range, language)
                Box(Modifier.weight(1f).fillMaxHeight().testTag("memory-inspector-card")) {
                    MemoryObjectInspector(state, actions, language, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun DominatorDetailsDivider(
    fraction: MutableState<Float>,
    paneWidth: Dp,
    range: ClosedFloatingPointRange<Float>,
    language: UiLanguage,
) {
    val density = LocalDensity.current
    val paneWidthPx = with(density) { paneWidth.toPx() }
    val label =
        localizedStringResource(Res.string.dominator_tree, language) + " / " +
            localizedStringResource(Res.string.instance_details, language)
    Box(
        Modifier
            .width(SPLITTER_WIDTH)
            .fillMaxHeight()
            .testTag("memory-dominator-details-resizer")
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(paneWidthPx) {
                detectHorizontalDragGestures { change, amount ->
                    change.consume()
                    if (paneWidthPx > 0f) {
                        fraction.value = (fraction.value.coerceIn(range) + amount / paneWidthPx).coerceIn(range)
                    }
                }
            }.semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.value.coerceIn(range), range)
                setProgress {
                    fraction.value = it.coerceIn(range)
                    true
                }
            }.onKeyEvent { event ->
                val direction =
                    when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                if (event.type == KeyEventType.KeyDown && direction != 0 && paneWidthPx > 0f) {
                    val step = with(density) { KEYBOARD_STEP.toPx() } / paneWidthPx
                    fraction.value = (fraction.value.coerceIn(range) + direction * step).coerceIn(range)
                    true
                } else {
                    false
                }
            }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(Modifier.fillMaxHeight().testTag("memory-dominator-details-divider-line"))
    }
}
