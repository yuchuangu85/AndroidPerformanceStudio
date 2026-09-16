package com.androidperformancestudio.desktop

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color

/**
 * Source-code palette adapted from JetBrains One Dark.
 *
 * Upstream: https://github.com/one-dark/jetbrains-one-dark-theme
 * Copyright (c) 2025-2026 Mark Skelton. Licensed under the ISC License.
 */
internal object OneDarkSourceTheme {
    val editorBackground = Color(0xFF282C34)
    val foreground = Color(0xFFABB2BF)
    val lineNumber = Color(0xFF495162)
    val currentLine = Color(0xFF2C313C)
    val selection = Color(0xFF404859)
    val searchMatch = Color(0xFF5C4E20)
    val currentSearchMatch = Color(0xFF3E638C)
    val scrollbar = Color(0xFF4B575F)
    val scrollbarHover = Color(0xFF606368)

    val keyword = Color(0xFFC678DD)
    val string = Color(0xFF98C379)
    val number = Color(0xFFD19A66)
    val comment = Color(0xFF5C6370)
    val annotation = Color(0xFFE5C07B)
    val tag = Color(0xFFE06C75)
    val attribute = Color(0xFFD19A66)

    val textSelectionColors = TextSelectionColors(
        handleColor = Color(0xFF568AF2),
        backgroundColor = selection,
    )

    fun colorFor(token: SourceTokenKind): Color =
        when (token) {
            SourceTokenKind.KEYWORD,
            SourceTokenKind.PREPROCESSOR,
            -> keyword
            SourceTokenKind.STRING,
            SourceTokenKind.VALUE,
            -> string
            SourceTokenKind.NUMBER -> number
            SourceTokenKind.ATTRIBUTE -> attribute
            SourceTokenKind.COMMENT -> comment
            SourceTokenKind.ANNOTATION -> annotation
            SourceTokenKind.TAG -> tag
        }
}
