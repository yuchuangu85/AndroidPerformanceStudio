package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.CallNodePath
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt

internal sealed interface FlameGraphContextCommand {
    data class ApplyTransform(
        val transform: CallStackTransform,
    ) : FlameGraphContextCommand

    data class Copy(
        val text: String,
    ) : FlameGraphContextCommand

    data object Undo : FlameGraphContextCommand

    data object Clear : FlameGraphContextCommand
}

internal data class FlameGraphContextEntry(
    val label: String,
    val shortcut: String?,
    val command: FlameGraphContextCommand,
    val labelResource: StringResource? = null,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
)

internal object FlameGraphContextCommands {
    @Suppress("LongMethod")
    fun entries(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        hasTransforms: Boolean,
    ): List<FlameGraphContextEntry> {
        val table = snapshot.callNodes
        val nodeIndex = table.indexOf(nodeId)
        val frame = nodeIndex?.let(table::frameAt)
        if (nodeIndex == null || frame == null) return emptyList()
        val path = pathFor(snapshot, nodeId)
        val function = frame.functionId
        val category = table.categoryAt(nodeIndex)?.takeIf(String::isNotBlank)
        val resource = frame.resource.takeIf { value -> value.isNotBlank() && frame.collapsedResource == null }
        return buildList {
            transform(SimpleperfViewerRes.sp_flame_merge_function, "m", CallStackTransform.MergeFunction(function))
            if (snapshot.query.direction == CallStackDirection.FORWARD && path != null) {
                transform(
                    SimpleperfViewerRes.sp_flame_merge_node_only,
                    "M",
                    CallStackTransform.MergeCallNode(path, snapshot.query.implementation),
                )
            }
            transform(SimpleperfViewerRes.sp_flame_focus_on_function, "f", CallStackTransform.FocusFunction(function))
            if (path != null) {
                transform(
                    SimpleperfViewerRes.sp_flame_focus_on_call_node,
                    "F",
                    CallStackTransform.FocusCallNode(path, snapshot.query.implementation, snapshot.query.direction),
                )
            }
            transform(
                SimpleperfViewerRes.sp_flame_focus_on_self_only,
                "S",
                CallStackTransform.FocusFunctionSelf(function),
            )
            category?.let { value ->
                transform(SimpleperfViewerRes.sp_flame_focus_on_category, "g", CallStackTransform.FocusCategory(value))
            }
            transform(
                SimpleperfViewerRes.sp_flame_collapse_function_subtree,
                "c",
                CallStackTransform.CollapseFunctionSubtree(function),
            )
            resource?.let { value ->
                transform(
                    SimpleperfViewerRes.sp_flame_collapse_resource,
                    "C",
                    CallStackTransform.CollapseResource(value),
                )
            }
            if (table.hasRecursiveCall(function)) {
                transform(
                    SimpleperfViewerRes.sp_flame_collapse_recursion,
                    "r",
                    CallStackTransform.CollapseRecursion(function),
                )
            }
            if (table.hasDirectRecursiveCall(function)) {
                transform(
                    SimpleperfViewerRes.sp_flame_collapse_direct_recursion_only,
                    "R",
                    CallStackTransform.CollapseDirectRecursion(function),
                )
            }
            transform(
                SimpleperfViewerRes.sp_flame_drop_samples_with_function,
                "d",
                CallStackTransform.DropFunction(function),
            )
            add(
                FlameGraphContextEntry(
                    label = "",
                    shortcut = null,
                    command = FlameGraphContextCommand.Copy(frame.symbolName),
                    labelResource = SimpleperfViewerRes.sp_flame_copy_function_name,
                    separatorBefore = true,
                ),
            )
            add(
                FlameGraphContextEntry(
                    label = "",
                    shortcut = null,
                    command = FlameGraphContextCommand.Copy(copyStack(table, nodeIndex)),
                    labelResource = SimpleperfViewerRes.sp_flame_copy_stack,
                ),
            )
            if (hasTransforms) {
                add(
                    FlameGraphContextEntry(
                        label = "",
                        shortcut = null,
                        command = FlameGraphContextCommand.Undo,
                        labelResource = SimpleperfViewerRes.sp_flame_undo_last_transform,
                        separatorBefore = true,
                    ),
                )
                add(
                    FlameGraphContextEntry(
                        label = "",
                        shortcut = null,
                        command = FlameGraphContextCommand.Clear,
                        labelResource = SimpleperfViewerRes.sp_flame_clear_transforms,
                    ),
                )
            }
        }
    }

    private fun copyStack(
        table: CallNodeTable,
        nodeIndex: Int,
    ): String =
        buildList {
            var current: Int? = nodeIndex
            while (current != null && current >= 0) {
                val frame = table.frameAt(current) ?: break
                add(
                    frame.symbolName +
                        frame.resource
                            .takeIf(String::isNotBlank)
                            ?.let { " [$it]" }
                            .orEmpty(),
                )
                current = table.parentIndexAt(current)
            }
        }.joinToString("\n")

    fun pathFor(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
    ): CallNodePath? {
        val table = snapshot.callNodes
        var nodeIndex = table.indexOf(nodeId) ?: return null
        val reversed = ArrayList<FlameFunctionId>()
        var validPath = true
        while (nodeIndex >= 0 && validPath) {
            val frame = table.frameAt(nodeIndex)
            if (frame == null) {
                validPath = false
            } else {
                reversed += frame.functionId
                nodeIndex = table.parentIndexAt(nodeIndex) ?: -1
            }
        }
        return if (validPath) CallNodePath(reversed.reversed()) else null
    }

    fun commandForShortcut(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        key: Key,
        shiftPressed: Boolean,
    ): FlameGraphContextCommand? {
        val shortcut = shortcutLabel(key, shiftPressed) ?: return null
        return entries(snapshot, nodeId, hasTransforms = false)
            .firstOrNull { entry -> entry.enabled && entry.shortcut == shortcut }
            ?.command
    }

    fun nextEnabledIndex(
        entries: List<FlameGraphContextEntry>,
        current: Int,
        direction: Int,
    ): Int {
        if (entries.isEmpty()) return -1
        val start = if (current < 0 && direction < 0) 0 else current
        return (1..entries.size)
            .map { step -> Math.floorMod(start + step * direction, entries.size) }
            .firstOrNull { index -> entries[index].enabled } ?: -1
    }

    fun shortcutLabel(
        key: Key,
        shiftPressed: Boolean,
    ): String? = TRANSFORM_SHORTCUTS[Shortcut(key, shiftPressed)]

    private fun MutableList<FlameGraphContextEntry>.transform(
        labelResource: StringResource,
        shortcut: String,
        transform: CallStackTransform,
    ) {
        add(FlameGraphContextEntry("", shortcut, FlameGraphContextCommand.ApplyTransform(transform), labelResource))
    }
}

private data class Shortcut(
    val key: Key,
    val shiftPressed: Boolean,
)

private val TRANSFORM_SHORTCUTS =
    mapOf(
        Shortcut(Key.F, true) to "F",
        Shortcut(Key.F, false) to "f",
        Shortcut(Key.S, true) to "S",
        Shortcut(Key.M, true) to "M",
        Shortcut(Key.M, false) to "m",
        Shortcut(Key.D, false) to "d",
        Shortcut(Key.C, true) to "C",
        Shortcut(Key.C, false) to "c",
        Shortcut(Key.R, true) to "R",
        Shortcut(Key.R, false) to "r",
        Shortcut(Key.G, false) to "g",
    )

@Composable
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "ktlint:standard:function-naming")
internal fun FirefoxFlameGraphContextMenu(
    entries: List<FlameGraphContextEntry>,
    anchor: Offset,
    style: FirefoxFlameGraphStyle,
    onCommand: (FlameGraphContextCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var highlightedIndex by remember(entries) { mutableIntStateOf(-1) }
    val maximumHeight =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp() * MENU_MAX_HEIGHT_RATIO
        }
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        LaunchedEffect(entries) { focusRequester.requestFocus() }
        Surface(
            modifier =
                Modifier
                    .widthIn(min = 160.dp, max = 600.dp)
                    .width(IntrinsicSize.Max)
                    .heightIn(max = maximumHeight),
            shape = RoundedCornerShape(5.dp),
            color = style.raisedSurface.toComposeColor(),
            contentColor = style.canvasForeground.toComposeColor(),
            border = BorderStroke(1.dp, style.surfaceBorder.toComposeColor()),
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .testTag("flame-context-menu")
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key == Key.Escape || event.key == Key.Tab) {
                                onDismiss()
                                return@onPreviewKeyEvent true
                            }
                            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) {
                                return@onPreviewKeyEvent false
                            }
                            when (event.key) {
                                Key.DirectionDown ->
                                    highlightedIndex =
                                        FlameGraphContextCommands.nextEnabledIndex(entries, highlightedIndex, 1)
                                Key.DirectionUp ->
                                    highlightedIndex =
                                        FlameGraphContextCommands.nextEnabledIndex(entries, highlightedIndex, -1)
                                Key.MoveHome -> highlightedIndex = entries.indexOfFirst { it.enabled }
                                Key.MoveEnd -> highlightedIndex = entries.indexOfLast { it.enabled }
                                Key.Enter, Key.NumPadEnter, Key.Spacebar ->
                                    entries
                                        .getOrNull(highlightedIndex)
                                        ?.takeIf { it.enabled }
                                        ?.let { onCommand(it.command) }
                                else -> {
                                    val shortcut =
                                        FlameGraphContextCommands.shortcutLabel(event.key, event.isShiftPressed)
                                    val entry =
                                        entries.firstOrNull {
                                            it.enabled && it.shortcut != null && it.shortcut == shortcut
                                        }
                                    if (entry == null) return@onPreviewKeyEvent false
                                    onCommand(entry.command)
                                }
                            }
                            true
                        }.focusable()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 5.dp),
            ) {
                entries.forEachIndexed { index, entry ->
                    if (entry.separatorBefore) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            color = style.surfaceBorder.toComposeColor(),
                        )
                    }
                    val highlighted = entry.enabled && highlightedIndex == index
                    val bringIntoViewRequester = remember { BringIntoViewRequester() }
                    LaunchedEffect(highlighted) {
                        if (highlighted) bringIntoViewRequester.bringIntoView()
                    }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("flame-context-entry-$index")
                                .bringIntoViewRequester(bringIntoViewRequester)
                                .semantics { selected = highlighted }
                                .background(if (highlighted) style.focusOutline.toComposeColor() else Color.Transparent)
                                .onPointerEvent(PointerEventType.Enter) {
                                    highlightedIndex = if (entry.enabled) index else -1
                                }.clickable(enabled = entry.enabled, role = Role.Button) { onCommand(entry.command) }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (entry.labelResource != null) {
                                localizedStringResource(
                                    entry.labelResource,
                                    currentSimpleperfLanguage(),
                                )
                            } else {
                                entry.label
                            },
                            modifier = Modifier.weight(1f),
                            color =
                                when {
                                    !entry.enabled -> style.mutedForeground.toComposeColor()
                                    highlighted -> Color.White
                                    else -> style.canvasForeground.toComposeColor()
                                },
                            fontSize = ViewerTypography.secondary.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        entry.shortcut?.let { shortcut ->
                            Text(
                                shortcut,
                                modifier =
                                    Modifier
                                        .background(
                                            style.controlSelectedSurface.toComposeColor(),
                                            RoundedCornerShape(3.dp),
                                        ).padding(horizontal = 5.dp),
                                color = style.canvasForeground.toComposeColor(),
                                fontSize = ViewerTypography.label.fontSize,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MENU_MAX_HEIGHT_RATIO = 0.9f

private fun CallNodeTable.hasDirectRecursiveCall(function: FlameFunctionId): Boolean =
    (0 until size).any { nodeIndex ->
        frameAt(nodeIndex)?.functionId == function &&
            parentIndexAt(nodeIndex)
                ?.let(::frameAt)
                ?.functionId == function
    }

private fun CallNodeTable.hasRecursiveCall(function: FlameFunctionId): Boolean =
    (0 until size).any { nodeIndex ->
        if (frameAt(nodeIndex)?.functionId != function) return@any false
        var ancestorIndex = parentIndexAt(nodeIndex)
        while (ancestorIndex != null && ancestorIndex >= 0) {
            if (frameAt(ancestorIndex)?.functionId == function) return@any true
            ancestorIndex = parentIndexAt(ancestorIndex)
        }
        false
    }
