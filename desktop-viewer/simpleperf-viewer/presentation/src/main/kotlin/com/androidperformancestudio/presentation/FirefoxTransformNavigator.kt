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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.ui.localizedStringResource
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
    val language = currentSimpleperfLanguage()
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
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_flame_transforms, language),
            color = style.mutedForeground.toComposeColor(),
            fontSize = 10.sp,
        )
        transforms.forEachIndexed { index, transform ->
            if (index > 0) Text("›", color = style.mutedForeground.toComposeColor(), fontSize = 10.sp)
            Text(
                transform.navigatorLabel(currentSimpleperfLanguage()),
                color = style.canvasForeground.toComposeColor(),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_flame_undo, language),
            modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 5.dp, vertical = 2.dp),
            color = style.canvasForeground.toComposeColor(),
            fontSize = 10.sp,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_details_clear, language),
            modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 5.dp, vertical = 2.dp),
            color = style.canvasForeground.toComposeColor(),
            fontSize = 10.sp,
        )
    }
}

internal fun CallStackTransform.navigatorLabel(
    language: com.androidperformancestudio.ui.UiLanguage = com.androidperformancestudio.ui.UiLanguage.ENGLISH,
): String =
    when (this) {
        is CallStackTransform.FocusCallNode ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_focus_call_node, language)
        is CallStackTransform.FocusFunction ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_focus_function, language)
        is CallStackTransform.FocusFunctionSelf ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_focus_self, language)
        is CallStackTransform.MergeCallNode ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_merge_call_node, language)
        is CallStackTransform.MergeFunction ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_merge_function, language)
        is CallStackTransform.DropFunction ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_drop_function, language)
        is CallStackTransform.CollapseResource ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_collapse_resource, language, resource.substringAfterLast('/'))
        is CallStackTransform.CollapseRecursion ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_collapse_recursion, language)
        is CallStackTransform.CollapseDirectRecursion ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_collapse_direct_recursion, language)
        is CallStackTransform.CollapseFunctionSubtree ->
            localizedStringResource(SimpleperfViewerRes.sp_flame_nav_collapse_subtree, language)
        is CallStackTransform.FocusCategory ->
            localizedStringResource(SimpleperfViewerRes.sp_details_category_value_format, language, category)
    }

private const val TRANSFORM_ROW_HEIGHT_DP = 25
