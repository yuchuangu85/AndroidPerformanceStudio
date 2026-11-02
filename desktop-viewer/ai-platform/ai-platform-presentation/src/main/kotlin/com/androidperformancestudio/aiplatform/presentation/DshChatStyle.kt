package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Chat-local DSH 0.2.1-rc.5 tokens; these do not change ViewerTheme. */
@Immutable
internal data class DshChatColors(
    val background: Color,
    val text: Color,
    val muted: Color,
    val sidebar: Color,
    val bubble: Color,
    val composer: Color,
    val send: Color,
    val onSend: Color,
    val border: Color,
    val error: Color,
) {
    companion object {
        val Light =
            DshChatColors(
                background = Color.White,
                text = Color(0xFF0F1115),
                muted = Color(0xFF686B73),
                sidebar = Color(0xFFF7F7F8),
                bubble = Color(0xFFEDF3FE),
                composer = Color.White,
                send = Color(0xFF4176E6),
                onSend = Color.White,
                border = Color(0xFFE5E5E9),
                error = Color(0xFFB42332),
            )
        val Dark =
            DshChatColors(
                background = Color(0xFF151517),
                text = Color(0xFFF9FAFB),
                muted = Color(0xFFABABB3),
                sidebar = Color(0xFF202024),
                bubble = Color(0xFF2C2C2E),
                composer = Color(0xFF2C2C2E),
                send = Color(0xFF7AAAFF),
                onSend = Color(0xFF151517),
                border = Color(0xFF3C3C42),
                error = Color(0xFFFFA2AB),
            )
    }
}

internal val LocalDshChatColors = staticCompositionLocalOf { DshChatColors.Light }

internal object DshChatStyle {
    val sidebarWidth = 260.dp
    val smallRadius = 8.dp
    val largeRadius = 16.dp
    val extraLargeRadius = 20.dp
    val panelRadius = 28.dp
    val sendButtonSize = 28.dp
    val scrollButtonSize = 32.dp
    val scrollButtonGap = 6.dp
    val scrollControlsInset = 12.dp
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 24.sp, letterSpacing = 0.sp)
    val user = body.copy(lineHeight = 22.sp)
    val secondary = body.copy(fontSize = 12.sp, lineHeight = 18.sp)
    val title = body.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)

    fun chatWidth(available: Dp): Dp = (available * 0.64f).coerceIn(680.dp, 920.dp).coerceAtMost((available - 48.dp).coerceAtLeast(0.dp))

    fun composerWidth(available: Dp): Dp = (chatWidth(available) + 32.dp).coerceAtMost((available - 16.dp).coerceAtLeast(0.dp))
}
