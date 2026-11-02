@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.input_error
import org.jetbrains.compose.resources.stringResource

/** Desktop form field. Framework editing/decoration is retained; labels never crowd the input. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun ViewerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = ViewerTypography.bodyCompact,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(ViewerDimensions.controlRadius),
    colors: TextFieldColors = viewerOutlinedTextFieldColors(),
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val errorMessage = stringResource(Res.string.input_error)
    val inputColor = colors.viewerTextColor(enabled, isError, focused)
    val labelColor = colors.viewerLabelColor(enabled, isError, focused)
    val supportColor = colors.viewerSupportingColor(enabled, isError, focused)
    CompositionLocalProvider(
        LocalTextSelectionColors provides colors.textSelectionColors,
        LocalMinimumInteractiveComponentSize provides ViewerDimensions.textFieldHeight,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                modifier
                    .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth)
                    .semantics { if (isError) error(errorMessage) },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = textStyle.color.takeOrElse { inputColor }),
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = interactions,
            decorationBox = { innerTextField ->
                Column(Modifier.fillMaxWidth()) {
                    if (label != null) {
                        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            CompositionLocalProvider(LocalContentColor provides labelColor) {
                                ProvideTextStyle(ViewerTypography.label, label)
                            }
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = ViewerDimensions.textFieldHeight)
                                .testTag("viewer-text-field-input"),
                        propagateMinConstraints = true,
                    ) {
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = value,
                            innerTextField = innerTextField,
                            enabled = enabled,
                            singleLine = singleLine,
                            visualTransformation = visualTransformation,
                            interactionSource = interactions,
                            isError = isError,
                            placeholder = placeholder,
                            leadingIcon = leadingIcon,
                            trailingIcon = trailingIcon,
                            prefix = prefix,
                            suffix = suffix,
                            colors = colors,
                            contentPadding =
                                PaddingValues(
                                    horizontal = ViewerDimensions.textFieldHorizontalPadding,
                                    vertical = ViewerDimensions.textFieldVerticalPadding,
                                ),
                            container = {
                                OutlinedTextFieldDefaults.Container(
                                    enabled,
                                    isError,
                                    interactions,
                                    modifier = Modifier.testTag("viewer-text-field-outline"),
                                    colors = colors,
                                    shape = shape,
                                )
                            },
                        )
                    }
                    if (supportingText != null) {
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            CompositionLocalProvider(LocalContentColor provides supportColor) {
                                ProvideTextStyle(ViewerTypography.secondary, supportingText)
                            }
                        }
                    }
                }
            },
        )
    }
}

private fun TextFieldColors.viewerTextColor(
    enabled: Boolean,
    isError: Boolean,
    focused: Boolean,
): Color =
    when {
        !enabled -> disabledTextColor
        isError -> errorTextColor
        focused -> focusedTextColor
        else -> unfocusedTextColor
    }

private fun TextFieldColors.viewerLabelColor(
    enabled: Boolean,
    isError: Boolean,
    focused: Boolean,
): Color =
    when {
        !enabled -> disabledLabelColor
        isError -> errorLabelColor
        focused -> focusedLabelColor
        else -> unfocusedLabelColor
    }

private fun TextFieldColors.viewerSupportingColor(
    enabled: Boolean,
    isError: Boolean,
    focused: Boolean,
): Color =
    when {
        !enabled -> disabledSupportingTextColor
        isError -> errorSupportingTextColor
        focused -> focusedSupportingTextColor
        else -> unfocusedSupportingTextColor
    }
