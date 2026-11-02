@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.ui

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.copy_full_message
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.util.Locale

/** Selectable feedback with an explicit full-message copy action, independent of visual ellipsis. */
@Composable
public fun CopyableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    language: UiLanguage = UiLanguage.fromLocale(Locale.getDefault()),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyLabel = localizedStringResource(Res.string.copy_full_message, language)
    val copyMessage: () -> Unit = {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            clipboard.setClipEntry(ClipEntry(StringSelection(text)))
        }
    }
    ContextMenuDataProvider(items = { listOf(ContextMenuItem(copyLabel, copyMessage)) }) {
        // Preserve the tagged Text contract for callers and accessibility, not a textless wrapper.
        SelectionContainer(modifier = modifier.semantics(mergeDescendants = true) {}) {
            Text(
                text = text,
                modifier =
                    Modifier.semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(copyLabel) {
                                    copyMessage()
                                    true
                                },
                            )
                    },
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                fontFamily = fontFamily,
                textAlign = textAlign,
                lineHeight = lineHeight,
                overflow = overflow,
                softWrap = softWrap,
                maxLines = maxLines,
                minLines = minLines,
                onTextLayout = onTextLayout,
                style = style,
            )
        }
    }
}
