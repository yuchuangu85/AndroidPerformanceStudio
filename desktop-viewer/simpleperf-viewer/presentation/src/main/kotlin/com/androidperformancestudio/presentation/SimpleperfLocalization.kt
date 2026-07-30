@file:Suppress("FunctionName")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.androidperformancestudio.ui.UiLanguage

private val LocalSimpleperfLanguage = staticCompositionLocalOf { UiLanguage.ENGLISH }

@Composable
internal fun SimpleperfLocalization(
    language: UiLanguage,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSimpleperfLanguage provides language, content = content)
}

@Composable
internal fun currentSimpleperfLanguage(): UiLanguage = LocalSimpleperfLanguage.current
