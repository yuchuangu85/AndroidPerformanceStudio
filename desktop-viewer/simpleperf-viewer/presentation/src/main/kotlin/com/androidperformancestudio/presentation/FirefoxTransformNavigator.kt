package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxTransformNavigator(
    transforms: List<CallStackTransform>,
    style: FirefoxFlameGraphStyle,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    if (transforms.isEmpty()) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TRANSFORM_ROW_HEIGHT_DP.dp)
                .background(style.panelSurface.toComposeColor())
                .border(1.dp, style.viewportBorder.toComposeColor())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Transforms", color = style.mutedForeground.toComposeColor(), fontSize = 10.sp)
        transforms.forEachIndexed { index, transform ->
            if (index > 0) Text("›", color = style.mutedForeground.toComposeColor(), fontSize = 10.sp)
            Text(
                transform.navigatorLabel(),
                color = style.canvasForeground.toComposeColor(),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Undo",
            modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 5.dp, vertical = 2.dp),
            color = style.canvasForeground.toComposeColor(),
            fontSize = 10.sp,
        )
        Text(
            "Clear",
            modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 5.dp, vertical = 2.dp),
            color = style.canvasForeground.toComposeColor(),
            fontSize = 10.sp,
        )
    }
}

internal fun CallStackTransform.navigatorLabel(): String =
    when (this) {
        is CallStackTransform.FocusCallNode -> "Focus call node"
        is CallStackTransform.FocusFunction -> "Focus function"
        is CallStackTransform.FocusFunctionSelf -> "Focus self"
        is CallStackTransform.MergeCallNode -> "Merge call node"
        is CallStackTransform.MergeFunction -> "Merge function"
        is CallStackTransform.DropFunction -> "Drop function"
        is CallStackTransform.CollapseResource -> "Collapse ${resource.substringAfterLast('/')}"
        is CallStackTransform.CollapseRecursion -> "Collapse recursion"
        is CallStackTransform.CollapseDirectRecursion -> "Collapse direct recursion"
        is CallStackTransform.CollapseFunctionSubtree -> "Collapse subtree"
        is CallStackTransform.FocusCategory -> "Category: $category"
    }

private const val TRANSFORM_ROW_HEIGHT_DP = 25
