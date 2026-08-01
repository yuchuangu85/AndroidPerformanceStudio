package com.androidperformancestudio.ui.switch

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun MacOSSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedTrackColor: Color = Color(0xFF34C759),
    uncheckedTrackColor: Color = Color(0xFFE5E5EA),
    thumbColor: Color = Color.White,
) {
    val transition = updateTransition(
        targetState = checked,
        label = "IosSwitchTransition"
    )

    val trackColor by transition.animateColor(
        transitionSpec = {
            tween(
                durationMillis = 220,
                easing = FastOutSlowInEasing
            )
        },
        label = "IosSwitchTrackColor"
    ) { isChecked ->
        if (isChecked) checkedTrackColor else uncheckedTrackColor
    }

    val thumbOffset by transition.animateDp(
        transitionSpec = {
            tween(
                durationMillis = 220,
                easing = FastOutSlowInEasing
            )
        },
        label = "IosSwitchThumbOffset"
    ) { isChecked ->
        if (isChecked) {
            22.dp
        } else {
            2.dp
        }
    }

    val interactionSource = remember {
        MutableInteractionSource()
    }

    /*
     * 外层高度为 48dp，保证触摸区域足够大；
     * 内部可见 Switch 保持 iOS 常用的 51dp × 31dp 比例。
     */
    Box(
        modifier = modifier
            .size(
                width = 51.dp,
                height = 48.dp
            )
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = 51.dp,
                    height = 31.dp
                )
                .background(
                    color = trackColor,
                    shape = CircleShape
                )
        ) {
            Box(
                modifier = Modifier
                    .offset(
                        x = thumbOffset,
                        y = 2.dp
                    )
                    .size(27.dp)
                    .shadow(
                        elevation = 2.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .background(
                        color = thumbColor,
                        shape = CircleShape
                    )
            )
        }
    }
}
