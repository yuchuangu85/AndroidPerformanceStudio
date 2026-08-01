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
    val trackWidth = 36.dp
    val trackHeight = 22.dp
    val thumbSize = 20.dp
    val horizontalPadding = 2.dp

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

    val thumbOffsetX by transition.animateDp(
        transitionSpec = {
            tween(
                durationMillis = 220,
                easing = FastOutSlowInEasing
            )
        },
        label = "MacOSSwitchThumbOffset"
    ) { isChecked ->
        if (isChecked) {
            trackWidth - thumbSize - horizontalPadding
        } else {
            horizontalPadding
        }
    }

    val interactionSource = remember {
        MutableInteractionSource()
    }

    Box(
        modifier = modifier
            .size(
                width = trackWidth,
                height = 32.dp
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
                    width = trackWidth,
                    height = trackHeight
                )
                .background(
                    color = trackColor,
                    shape = CircleShape
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffsetX)
                    .size(thumbSize)
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
