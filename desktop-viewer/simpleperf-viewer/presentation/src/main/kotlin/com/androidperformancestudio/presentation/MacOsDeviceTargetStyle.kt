@file:Suppress("MagicNumber")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
internal data class MacOsDeviceTargetStyle(
    val workspace: Color,
    val toolbar: Color,
    val sidebar: Color,
    val panel: Color,
    val field: Color,
    val border: Color,
    val strongBorder: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val accentText: Color,
    val online: Color,
    val warning: Color,
    val error: Color,
)

internal object MacOsDeviceTargetDimensions {
    val toolbarHeight = 58.dp
    val sidebarWidth = 270.dp
    val compactSidebarWidth = 224.dp
    val capabilityHeight = 116.dp
    val footerHeight = 58.dp
    val buttonHeight = 28.dp
    val searchHeight = 30.dp
    val deviceRowHeight = 58.dp
    val targetRowHeight = 38.dp
    val panelRadius = 9.dp
    val controlRadius = 6.dp
    val hairline = 1.dp
}

internal fun macOsDeviceTargetStyle(dark: Boolean): MacOsDeviceTargetStyle =
    if (dark) {
        MacOsDeviceTargetStyle(
            workspace = Color(0xFF1E1E20),
            toolbar = Color(0xFF29292B),
            sidebar = Color(0xFF252527),
            panel = Color(0xFF2C2C2E),
            field = Color(0xFF1C1C1E),
            border = Color(0xFF48484A),
            strongBorder = Color(0xFF636366),
            text = Color(0xFFF5F5F7),
            secondaryText = Color(0xFFAEAEB2),
            accent = Color(0xFF0A84FF),
            accentText = Color.White,
            online = Color(0xFF30D158),
            warning = Color(0xFFFF9F0A),
            error = Color(0xFFFF453A),
        )
    } else {
        MacOsDeviceTargetStyle(
            workspace = Color(0xFFF5F5F7),
            toolbar = Color(0xFFFAFAFB),
            sidebar = Color(0xFFEDEDEF),
            panel = Color.White,
            field = Color.White,
            border = Color(0xFFD1D1D6),
            strongBorder = Color(0xFFB8B8BD),
            text = Color(0xFF1D1D1F),
            secondaryText = Color(0xFF6E6E73),
            accent = Color(0xFF0A84FF),
            accentText = Color.White,
            online = Color(0xFF34C759),
            warning = Color(0xFFFF9F0A),
            error = Color(0xFFFF3B30),
        )
    }
