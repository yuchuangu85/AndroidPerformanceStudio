package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FirefoxStackToolbar(
    state: ReportState,
    actions: ReportActions,
    style: ViewerColors,
    searchFocusRequester: FocusRequester? = null,
    onSearchFocusChanged: (Boolean) -> Unit = {},
) {
    val language = currentSimpleperfLanguage()
    val sessionIdentity = state.lastReadyReport?.session?.directory
    var searchState by
        remember(sessionIdentity) {
            mutableStateOf(FlameSearchDraftState.initial(state.callStackQuery.searchText))
        }

    LaunchedEffect(sessionIdentity, state.callStackQuery.searchText) {
        searchState = searchState.acknowledge(state.callStackQuery.searchText)
    }
    LaunchedEffect(sessionIdentity, searchState.draft, searchState.authoritativeQuery) {
        if (searchState.shouldDispatch) {
            delay(STACK_SEARCH_DEBOUNCE_MILLIS.milliseconds)
            val query = searchState.draft
            searchState = searchState.markDispatched(query)
            actions.onFlameSearch(query)
        }
    }

    BoxWithConstraints(
        Modifier
            .testTag("stack-toolbar")
            .fillMaxWidth()
            .height(STACK_TOOLBAR_HEIGHT_DP.dp)
            .background(style.toolbar),
    ) {
        val viewportWidth = maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                Modifier.width(IntrinsicSize.Max).widthIn(min = viewportWidth).height(STACK_TOOLBAR_HEIGHT_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.selectableGroup().padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImplementationFilter.entries.forEach { filter ->
                        StackImplementationRadio(
                            label = filter.displayName(language),
                            selected = state.callStackQuery.implementation == filter,
                            onSelect = { actions.onFlameImplementation(filter) },
                            style = style,
                        )
                    }
                }
                Spacer(Modifier.width(1.dp).height(19.dp).background(style.border))
                Row(
                    Modifier.padding(start = 10.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.selectedTab == ReportTab.STACK_CHART) {
                        StackSettingCheckbox(
                            localizedStringResource(SimpleperfViewerRes.sp_stack_show_user_timing, language),
                            state.workspace.stackChartShowUserTimings,
                            actions.onStackChartShowUserTimings,
                            style,
                        )
                        StackSettingCheckbox(
                            localizedStringResource(SimpleperfViewerRes.sp_stack_same_widths, language),
                            state.workspace.stackChartSameWidths,
                            actions.onStackChartSameWidths,
                            style,
                        )
                    } else {
                        StackSettingCheckbox(
                            localizedStringResource(SimpleperfViewerRes.sp_calltree_invert_call_stack, language),
                            state.callStackQuery.direction == CallStackDirection.INVERTED,
                            { inverted ->
                                actions.onCallTreeDirection(
                                    if (inverted) CallStackDirection.INVERTED else CallStackDirection.FORWARD,
                                )
                            },
                            style,
                        )
                    }
                }
                state.callStackQuery.previewRange?.let { range ->
                    MacOSTextButton(
                        localizedStringResource(SimpleperfViewerRes.sp_stack_zoom_to_selection, language),
                        { actions.onTimeRange(range.startNanosInclusive, range.endNanosExclusive) },
                        style,
                        height = 20.dp,
                    )
                    MacOSTextButton(
                        localizedStringResource(SimpleperfViewerRes.sp_flame_cancel_preview, language),
                        actions.onCancelFlamePreview,
                        style,
                        height = 20.dp,
                    )
                }
                StackFilterInput(
                    label = localizedStringResource(SimpleperfViewerRes.sp_calltree_filter_stacks, language),
                    value = searchState.draft,
                    onValueChange = { searchState = searchState.edit(it) },
                    onClear = {
                        searchState = searchState.edit("").markDispatched("")
                        actions.onFlameSearch("")
                    },
                    modifier = Modifier.weight(1f).padding(start = 4.dp, end = 0.dp),
                    style = style,
                    externalFocusRequester = searchFocusRequester,
                    onFocusChanged = onSearchFocusChanged,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun StackImplementationRadio(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    style: ViewerColors,
) {
    Row(
        Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = stackSettingControlModifier(),
            colors = RadioButtonDefaults.colors(selectedColor = style.accent, unselectedColor = style.strongBorder),
        )
        Text(label, color = style.text, style = ViewerTypography.label, maxLines = 1)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun StackSettingCheckbox(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    style: ViewerColors,
) {
    Row(
        Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Checkbox(
            checked,
            onCheckedChange = null,
            modifier = stackSettingControlModifier(),
            colors = CheckboxDefaults.colors(checkedColor = style.accent, uncheckedColor = style.strongBorder),
        )
        Text(label, color = style.text, style = ViewerTypography.label, maxLines = 1)
    }
}

private fun stackSettingControlModifier(): Modifier =
    Modifier
        .size(STACK_SETTING_CONTROL_DP.dp)
        .wrapContentSize(unbounded = true)
        .requiredSize(MATERIAL_SETTING_CONTROL_DP.dp)
        .scale(STACK_SETTING_CONTROL_DP.toFloat() / MATERIAL_SETTING_CONTROL_DP)

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")
private fun StackFilterInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
    style: ViewerColors,
    externalFocusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val localFocusRequester = remember { FocusRequester() }
    val focusRequester = externalFocusRequester ?: localFocusRequester
    var input by remember { mutableStateOf(TextFieldValue(value)) }
    val clearLabel = localizedStringResource(SimpleperfViewerRes.sp_details_clear, currentSimpleperfLanguage())
    LaunchedEffect(value) {
        if (value != input.text) input = TextFieldValue(value, TextRange(value.length))
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = style.text, style = ViewerTypography.label, maxLines = 1)
        BasicTextField(
            value = input,
            onValueChange = {
                input = it
                onValueChange(it.text)
            },
            singleLine = true,
            textStyle = ViewerTypography.label.copy(color = style.text),
            cursorBrush = SolidColor(style.accent),
            modifier =
                Modifier
                    .weight(1f)
                    .widthIn(min = 180.dp)
                    .height(20.dp)
                    .testTag("stack-filter-input")
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        onFocusChanged(it.isFocused)
                        if (it.isFocused) input = input.copy(selection = TextRange(0, input.text.length))
                    }.semantics { contentDescription = label }
                    .background(style.field, RoundedCornerShape(2.dp))
                    .border(1.dp, style.strongBorder, RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            decorationBox = { innerField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { innerField() }
                    if (input.text.isNotEmpty()) {
                        Text(
                            "\u00d7",
                            color = style.mutedText,
                            style = ViewerTypography.label,
                            modifier =
                                Modifier
                                    .width(18.dp)
                                    .testTag("stack-filter-clear")
                                    .semantics { contentDescription = clearLabel }
                                    .clickable(role = Role.Button) {
                                        input = TextFieldValue("")
                                        focusRequester.requestFocus()
                                        onClear()
                                    },
                        )
                    }
                }
            },
        )
    }
}

private fun ImplementationFilter.displayName(language: UiLanguage): String =
    when (this) {
        ImplementationFilter.ALL -> localizedStringResource(SimpleperfViewerRes.sp_calltree_all_frames, language)
        ImplementationFilter.SCRIPT -> localizedStringResource(SimpleperfViewerRes.sp_calltree_script, language)
        ImplementationFilter.NATIVE -> localizedStringResource(SimpleperfViewerRes.sp_flame_native, language)
    }

private const val STACK_SEARCH_DEBOUNCE_MILLIS = 200L
private const val STACK_TOOLBAR_HEIGHT_DP = 25
private const val STACK_SETTING_CONTROL_DP = 13
private const val MATERIAL_SETTING_CONTROL_DP = 20

internal data class FlameSearchDraftState(
    val authoritativeQuery: String,
    val draft: String,
    private val pendingDispatches: List<String>,
) {
    val isDirty: Boolean
        get() = draft != authoritativeQuery

    val shouldDispatch: Boolean
        get() = isDirty && pendingDispatches.lastOrNull() != draft

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
