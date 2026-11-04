package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxTransformNavigator(
    transforms: List<CallStackTransform>,
    style: FirefoxFlameGraphStyle,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onRemoveAfter: ((Int) -> Unit)? = null,
) {
    val language = currentSimpleperfLanguage()
    val borderColor = style.viewportBorder.toComposeColor()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TRANSFORM_ROW_HEIGHT_DP.dp)
                .testTag("transform-navigator")
                .clipToBounds()
                .background(style.panelSurface.toComposeColor())
                .drawBehind { drawLine(borderColor, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransformCrumb(
            label = localizedStringResource(SimpleperfViewerRes.sp_flame_nav_complete, language),
            isLeaf = transforms.isEmpty(),
            onClick = if (transforms.isEmpty()) null else ({ onRemoveAfter?.invoke(0) ?: onClear() }),
            style = style,
            modifier = Modifier.testTag("transform-crumb-0"),
        )
        transforms.forEachIndexed { index, transform ->
            val retainedCount = index + 1
            val isLeaf = retainedCount == transforms.size
            val onPop: (() -> Unit)? =
                when {
                    isLeaf -> null
                    onRemoveAfter != null -> ({ onRemoveAfter(retainedCount) })
                    retainedCount == transforms.lastIndex -> onUndo
                    else -> null
                }
            TransformCrumb(
                label = transform.navigatorLabel(language),
                isLeaf = isLeaf,
                onClick = onPop,
                style = style,
                modifier = Modifier.weight(1f, fill = false).testTag("transform-crumb-$retainedCount"),
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TransformCrumb(
    label: String,
    isLeaf: Boolean,
    onClick: (() -> Unit)?,
    style: FirefoxFlameGraphStyle,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val foreground = style.canvasForeground.toComposeColor()
    val separator = style.surfaceBorder.toComposeColor()
    Text(
        label,
        modifier =
            modifier
                .height(TRANSFORM_ROW_HEIGHT_DP.dp)
                .semantics { selected = isLeaf }
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.hoverable(interaction).clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    },
                ).background(
                    foreground.copy(
                        alpha =
                            if (pressed) {
                                0.2f
                            } else if (hovered && onClick != null) {
                                0.1f
                            } else {
                                0f
                            },
                    ),
                ).drawBehind {
                    if (!isLeaf) {
                        val inset = 6.dp.toPx()
                        val center = Offset(size.width - 1.dp.toPx(), size.height / 2)
                        drawLine(separator, Offset(center.x - inset, 0f), center, 1.dp.toPx())
                        drawLine(separator, center, Offset(center.x - inset, size.height), 1.dp.toPx())
                    }
                }.padding(start = 8.dp, end = if (isLeaf) 8.dp else 16.dp, top = 5.dp),
        color = if (isLeaf) style.focusOutline.toComposeColor() else foreground,
        style = ViewerTypography.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
            localizedStringResource(
                SimpleperfViewerRes.sp_flame_nav_collapse_resource,
                language,
                resource.substringAfterLast('/'),
            )
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
