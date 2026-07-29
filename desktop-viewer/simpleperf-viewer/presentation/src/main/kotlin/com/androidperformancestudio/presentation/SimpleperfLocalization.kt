@file:Suppress(
    "FunctionName",
    "LongParameterList",
    "MatchingDeclarationName",
    "MaxLineLength",
    "ReturnCount",
)

package com.androidperformancestudio.presentation

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import org.jetbrains.compose.resources.StringResource
import androidx.compose.material3.Text as MaterialText
import java.util.Locale

enum class SimpleperfLanguage {
    SIMPLIFIED_CHINESE {
        override val locale: Locale = Locale.SIMPLIFIED_CHINESE
    },
    ENGLISH {
        override val locale: Locale = Locale.ENGLISH
    },
    ;

    abstract val locale: Locale
}

private val LocalSimpleperfLanguage = staticCompositionLocalOf { SimpleperfLanguage.ENGLISH }

@Composable
internal fun SimpleperfLocalization(
    language: SimpleperfLanguage,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSimpleperfLanguage provides language, content = content)
}

@Composable
internal fun localizedSimpleperfText(text: String): String = translateSimpleperfText(text, LocalSimpleperfLanguage.current)

@Composable
internal fun localizedSimpleperfResource(
    resource: StringResource,
    vararg args: Any?,
): String =
    localizedStringResource(
        resource,
        chinese = LocalSimpleperfLanguage.current == SimpleperfLanguage.SIMPLIFIED_CHINESE,
        *args,
    )

@Composable
internal fun currentSimpleperfLanguage(): SimpleperfLanguage = LocalSimpleperfLanguage.current

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = translateSimpleperfText(text, LocalSimpleperfLanguage.current),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
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

internal fun translateSimpleperfText(
    text: String,
    language: SimpleperfLanguage,
): String {
    if (language == SimpleperfLanguage.ENGLISH) return text
    SimpleperfTranslationMap.resourceFor(text)?.let { resource ->
        return localizedStringResource(resource, chinese = true)
    }
    INC_EXC_PATTERN.matchEntire(text)?.let { match ->
        return localizedStringResource(ViewerRes.sp_dynamic_inc_exc, true, match.groupValues[1], match.groupValues[2])
    }
    INCLUSIVE_SELF_PATTERN.matchEntire(text)?.let { match ->
        return localizedStringResource(ViewerRes.sp_dynamic_inc_exc, true, match.groupValues[1], match.groupValues[2])
    }
    SAMPLES_PATTERN.matchEntire(text)?.let { match ->
        return localizedStringResource(ViewerRes.sp_dynamic_samples, true, match.groupValues[1], match.groupValues[2])
    }
    CHINESE_PREFIXES.firstOrNull { text.startsWith(it.first) }?.let { (english, resource) ->
        return localizedStringResource(resource, true, text.removePrefix(english))
    }
    EVERY_EVENTS_PATTERN.matchEntire(text)?.let { match ->
        return localizedStringResource(ViewerRes.sp_dynamic_every_events, true, match.groupValues[1])
    }
    if (text.endsWith(" hotspot")) {
        return localizedStringResource(
            ViewerRes.sp_dynamic_hotspot,
            true,
            translateSimpleperfText(text.removeSuffix(" hotspot"), language),
        )
    }
    if (text.startsWith("• ")) {
        return localizedStringResource(
            ViewerRes.sp_dynamic_bullet,
            true,
            translateSimpleperfText(text.removePrefix("• "), language),
        )
    }
    return text
}

private val CHINESE_PREFIXES: List<Pair<String, StringResource>> =
    listOf(
        "Loading " to ViewerRes.sp_prefix_loading,
        "Language: " to ViewerRes.sp_prefix_language,
        "Theme: " to ViewerRes.sp_prefix_theme,
        "Selected target: " to ViewerRes.sp_prefix_selected_target,
        "ABI: " to ViewerRes.sp_prefix_abi,
        "Root: " to ViewerRes.sp_prefix_root,
        "Scope: " to ViewerRes.sp_prefix_scope,
        "Simpleperf: " to ViewerRes.sp_prefix_simpleperf,
        "Capture settings: " to ViewerRes.sp_prefix_capture_settings,
        "Events: " to ViewerRes.sp_prefix_events,
        "Limits: " to ViewerRes.sp_prefix_limits,
        "Event: " to ViewerRes.sp_prefix_event,
        "Rate: " to ViewerRes.sp_prefix_rate,
        "Duration: " to ViewerRes.sp_prefix_duration,
        "Call graph: " to ViewerRes.sp_prefix_call_graph,
        "Lost samples: " to ViewerRes.sp_prefix_lost_samples,
        "Unwind errors: " to ViewerRes.sp_prefix_unwind_errors,
        "Unknown symbols: " to ViewerRes.sp_prefix_unknown_symbols,
        "Empty stacks: " to ViewerRes.sp_prefix_empty_stacks,
        "Completed: " to ViewerRes.sp_prefix_completed,
        "Category: " to ViewerRes.sp_prefix_category,
        "Implementation: " to ViewerRes.sp_prefix_implementation,
        "Resource: " to ViewerRes.sp_prefix_resource,
        "Stack Type: " to ViewerRes.sp_prefix_stack_type,
        "Preview range weight: " to ViewerRes.sp_prefix_preview_range_weight,
        "Inclusive " to ViewerRes.sp_prefix_inclusive,
        "Exclusive " to ViewerRes.sp_prefix_exclusive,
        "Process: " to ViewerRes.sp_prefix_process,
        "Thread: " to ViewerRes.sp_prefix_thread,
        "Schema: " to ViewerRes.sp_prefix_schema,
        "Start: " to ViewerRes.sp_prefix_start,
        "End: " to ViewerRes.sp_prefix_end,
    )

private val INC_EXC_PATTERN = Regex("inc (.+) · exc (.+)")
private val INCLUSIVE_SELF_PATTERN = Regex("Inclusive (.+) · Self (.+)")
private val SAMPLES_PATTERN = Regex("Samples (.+) · (.+)")
private val EVERY_EVENTS_PATTERN = Regex("every (.+) events")
