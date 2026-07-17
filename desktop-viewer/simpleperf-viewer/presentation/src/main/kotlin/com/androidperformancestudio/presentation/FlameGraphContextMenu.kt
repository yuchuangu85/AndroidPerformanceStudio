package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.androidperformancestudio.profileanalysis.CallNodePath
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
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
)

internal object FlameGraphContextCommands {
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
            transform("Merge function", "m", CallStackTransform.MergeFunction(function))
            if (snapshot.query.direction == CallStackDirection.FORWARD && path != null) {
                transform("Merge node only", "M", CallStackTransform.MergeCallNode(path))
            }
            transform("Focus on function", "f", CallStackTransform.FocusFunction(function))
            if (snapshot.query.direction == CallStackDirection.FORWARD && path != null) {
                transform("Focus on call node", "F", CallStackTransform.FocusCallNode(path))
            }
            transform("Focus on self only", "S", CallStackTransform.FocusFunctionSelf(function))
            category?.let { value -> transform("Focus on category", "g", CallStackTransform.FocusCategory(value)) }
            transform("Collapse function subtree", "c", CallStackTransform.CollapseFunctionSubtree(function))
            resource?.let { value -> transform("Collapse resource", "C", CallStackTransform.CollapseResource(value)) }
            if (table.hasRecursiveCall(function)) {
                transform("Collapse recursion", "r", CallStackTransform.CollapseRecursion(function))
            }
            if (table.hasDirectRecursiveCall(function)) {
                transform(
                    "Collapse direct recursion only",
                    "R",
                    CallStackTransform.CollapseDirectRecursion(function),
                )
            }
            transform("Drop samples with this function", "d", CallStackTransform.DropFunction(function))
            add(FlameGraphContextEntry("Copy function name", null, FlameGraphContextCommand.Copy(frame.symbolName)))
            if (hasTransforms) {
                add(FlameGraphContextEntry("Undo last transform", null, FlameGraphContextCommand.Undo))
                add(FlameGraphContextEntry("Clear transforms", null, FlameGraphContextCommand.Clear))
            }
        }
    }

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
            .firstOrNull { entry -> entry.shortcut == shortcut }
            ?.command
    }

    fun shortcutLabel(
        key: Key,
        shiftPressed: Boolean,
    ): String? = TRANSFORM_SHORTCUTS[Shortcut(key, shiftPressed)]

    private fun MutableList<FlameGraphContextEntry>.transform(
        label: String,
        shortcut: String,
        transform: CallStackTransform,
    ) {
        add(FlameGraphContextEntry(label, shortcut, FlameGraphContextCommand.ApplyTransform(transform)))
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
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxFlameGraphContextMenu(
    entries: List<FlameGraphContextEntry>,
    anchor: Offset,
    style: FirefoxFlameGraphStyle,
    onCommand: (FlameGraphContextCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp),
            shape = RoundedCornerShape(2.dp),
            color = style.raisedSurface.toComposeColor(),
            contentColor = style.canvasForeground.toComposeColor(),
            border = BorderStroke(1.dp, style.surfaceBorder.toComposeColor()),
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 3.dp)) {
                entries.forEach { entry ->
                    Row(
                        modifier =
                            Modifier
                                .clickable { onCommand(entry.command) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.label, modifier = Modifier.weight(1f), fontSize = 11.sp)
                        entry.shortcut?.let { shortcut ->
                            Text(shortcut, color = style.mutedForeground.toComposeColor(), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

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
