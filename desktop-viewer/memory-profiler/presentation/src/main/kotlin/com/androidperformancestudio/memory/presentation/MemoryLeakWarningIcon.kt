@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspects
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_attention
import org.jetbrains.compose.resources.painterResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiRes

/** Shared Android Studio-style warning marker for class/object leak candidates. */
@Composable
internal fun MemoryLeakWarningIcon(
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(UiRes.drawable.ic_stat_attention),
        contentDescription = localizedStringResource(Res.string.leak_suspects, language),
        modifier = modifier.size(14.dp),
        tint = LocalViewerColors.current.warning,
    )
}
