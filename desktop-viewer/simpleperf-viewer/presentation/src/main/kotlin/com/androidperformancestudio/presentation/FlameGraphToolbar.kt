package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import kotlinx.coroutines.delay
import java.nio.file.Path

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun FirefoxFlameGraphToolbar(
    sessionIdentity: Path,
    authoritativeSearch: String,
    implementation: ImplementationFilter,
    direction: CallStackDirection,
    style: FirefoxFlameGraphStyle,
    hasTransforms: Boolean,
    onSearch: (String) -> Unit,
    onImplementation: (ImplementationFilter) -> Unit,
    onDirection: (CallStackDirection) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    var searchState by remember(sessionIdentity) { mutableStateOf(FlameSearchDraftState.initial(authoritativeSearch)) }

    LaunchedEffect(sessionIdentity, authoritativeSearch) {
        searchState = searchState.acknowledge(authoritativeSearch)
    }
    LaunchedEffect(sessionIdentity, searchState.draft, searchState.authoritativeQuery) {
        if (searchState.isDirty) {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val query = searchState.draft
            searchState = searchState.markDispatched(query)
            onSearch(query)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Direction", color = style.mutedForeground.toComposeColor(), fontSize = 11.sp)
        CallStackDirection.entries.forEach { option ->
            FirefoxChoiceControl(
                label = option.displayName(),
                selected = direction == option,
                style = style,
                onClick = { onDirection(option) },
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("Implementation", color = style.mutedForeground.toComposeColor(), fontSize = 11.sp)
        ImplementationFilter.entries.forEach { option ->
            FirefoxChoiceControl(
                label = option.displayName(),
                selected = implementation == option,
                style = style,
                onClick = { onImplementation(option) },
            )
        }
        if (hasTransforms) {
            Spacer(Modifier.width(4.dp))
            FirefoxActionControl("Undo", style, onUndo)
            FirefoxActionControl("Clear", style, onClear)
        }
        Spacer(Modifier.weight(1f))
        FirefoxSearchField(
            value = searchState.draft,
            style = style,
            onValueChange = { searchState = searchState.edit(it) },
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "UNUSED_PARAMETER", "ktlint:standard:function-naming")
internal fun FlameGraphToolbar(
    sessionIdentity: Path,
    authoritativeSearch: String,
    implementation: ImplementationFilter,
    direction: CallStackDirection,
    hasTransforms: Boolean,
    onSearch: (String) -> Unit,
    onImplementation: (ImplementationFilter) -> Unit,
    onDirection: (CallStackDirection) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    FirefoxFlameGraphToolbar(
        sessionIdentity = sessionIdentity,
        authoritativeSearch = authoritativeSearch,
        implementation = implementation,
        direction = direction,
        style = rememberFirefoxFlameGraphStyle(),
        hasTransforms = hasTransforms,
        onSearch = onSearch,
        onImplementation = onImplementation,
        onDirection = onDirection,
        onUndo = onUndo,
        onClear = onClear,
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxChoiceControl(
    label: String,
    selected: Boolean,
    style: FirefoxFlameGraphStyle,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier =
            Modifier
                .height(CONTROL_HEIGHT_DP.dp)
                .background(
                    color =
                        if (selected) {
                            style.controlSelectedSurface.toComposeColor()
                        } else {
                            style.panelSurface.toComposeColor()
                        },
                    shape = shape,
                ).border(1.dp, style.surfaceBorder.toComposeColor(), shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = style.canvasForeground.toComposeColor(),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxActionControl(
    label: String,
    style: FirefoxFlameGraphStyle,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(CONTROL_HEIGHT_DP.dp)
                .background(style.panelSurface.toComposeColor(), RoundedCornerShape(2.dp))
                .border(1.dp, style.surfaceBorder.toComposeColor(), RoundedCornerShape(2.dp))
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = style.canvasForeground.toComposeColor(), fontSize = 11.sp)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxSearchField(
    value: String,
    style: FirefoxFlameGraphStyle,
    onValueChange: (String) -> Unit,
) {
    val searchDescription = localizedSimpleperfText("Flame graph search")
    val clearDescription = localizedSimpleperfText("Clear search")
    val shape = RoundedCornerShape(2.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier
                .widthIn(min = SEARCH_MIN_WIDTH_DP.dp, max = SEARCH_MAX_WIDTH_DP.dp)
                .height(CONTROL_HEIGHT_DP.dp)
                .background(style.canvasBackground.toComposeColor(), shape)
                .border(1.dp, style.surfaceBorder.toComposeColor(), shape)
                .semantics { contentDescription = searchDescription },
        singleLine = true,
        textStyle = TextStyle(color = style.canvasForeground.toComposeColor(), fontSize = 11.sp),
        cursorBrush = SolidColor(style.canvasForeground.toComposeColor()),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 7.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            "Search function or library",
                            color = style.mutedForeground.toComposeColor(),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Text(
                        "×",
                        modifier =
                            Modifier
                                .clickable { onValueChange("") }
                                .semantics { contentDescription = clearDescription }
                                .padding(horizontal = 4.dp),
                        color = style.canvasForeground.toComposeColor(),
                        fontSize = 14.sp,
                    )
                }
            }
        },
    )
}

internal data class FlameSearchDraftState(
    val authoritativeQuery: String,
    val draft: String,
    private val pendingDispatches: List<String>,
) {
    val isDirty: Boolean
        get() = draft != authoritativeQuery

    fun edit(value: String): FlameSearchDraftState = copy(draft = value)

    fun markDispatched(value: String): FlameSearchDraftState = copy(pendingDispatches = pendingDispatches + value)

    fun acknowledge(value: String): FlameSearchDraftState {
        if (value == authoritativeQuery) return this
        val pendingIndex = pendingDispatches.indexOf(value)
        return if (pendingIndex >= 0) {
            copy(
                authoritativeQuery = value,
                pendingDispatches = pendingDispatches.drop(pendingIndex + 1),
            )
        } else {
            initial(value)
        }
    }

    companion object {
        fun initial(value: String): FlameSearchDraftState =
            FlameSearchDraftState(
                authoritativeQuery = value,
                draft = value,
                pendingDispatches = emptyList(),
            )
    }
}

private fun CallStackDirection.displayName(): String =
    when (this) {
        CallStackDirection.FORWARD -> "Forward"
        CallStackDirection.INVERTED -> "Inverted"
    }

private fun ImplementationFilter.displayName(): String =
    when (this) {
        ImplementationFilter.ALL -> "All Frames"
        ImplementationFilter.SCRIPT -> "Script"
        ImplementationFilter.NATIVE -> "Native"
    }

private const val SEARCH_DEBOUNCE_MILLIS = 150L
private const val CONTROL_HEIGHT_DP = 24
private const val SEARCH_MIN_WIDTH_DP = 180
private const val SEARCH_MAX_WIDTH_DP = 260
