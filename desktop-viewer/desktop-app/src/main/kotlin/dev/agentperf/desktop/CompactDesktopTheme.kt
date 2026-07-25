package dev.agentperf.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun compactDesktopTypography(): Typography =
    Typography(
        headlineMedium = TextStyle(fontSize = 18.sp, lineHeight = 22.sp),
        headlineSmall = TextStyle(fontSize = 16.sp, lineHeight = 20.sp),
        titleLarge = TextStyle(fontSize = 15.sp, lineHeight = 19.sp),
        titleMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
        titleSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        bodyLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
        labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
        labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
    )

internal fun compactDesktopShapes(): Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(3.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )
