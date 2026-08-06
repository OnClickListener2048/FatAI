package ai.fatai.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * OpenWebUI-style toggle: a 28x16dp pill track with a 12dp round thumb.
 * On state uses the theme's surface color for the track (black track/white
 * thumb in light mode, white track/black thumb in dark mode), exactly like
 * OpenWebUI's Switch component.
 */
@Composable
fun OpenWebUISwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackWidth: Dp = 28.dp,
    trackHeight: Dp = 16.dp,
    thumbSize: Dp = 12.dp,
    thumbPadding: Dp = 2.dp
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> if (dark) OpenWebUISwitchColors.trackOffDark else OpenWebUISwitchColors.trackOffLight
            checked -> if (dark) OpenWebUISwitchColors.trackOnDark else OpenWebUISwitchColors.trackOnLight
            else -> if (dark) OpenWebUISwitchColors.trackOffDark else OpenWebUISwitchColors.trackOffLight
        },
        animationSpec = tween(durationMillis = 150),
        label = "trackColor"
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> if (dark) OpenWebUISwitchColors.thumbOffDark else OpenWebUISwitchColors.thumbOffLight
            checked -> if (dark) OpenWebUISwitchColors.thumbOnDark else OpenWebUISwitchColors.thumbOnLight
            else -> if (dark) OpenWebUISwitchColors.thumbOffDark else OpenWebUISwitchColors.thumbOffLight
        },
        animationSpec = tween(durationMillis = 150),
        label = "thumbColor"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - thumbPadding else thumbPadding,
        animationSpec = tween(durationMillis = 150),
        label = "thumbOffset"
    )

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) }
            )
            .background(color = trackColor, shape = CircleShape)
            .then(
                if (!enabled) {
                    Modifier.border(
                        width = 1.dp,
                        color = if (dark) OpenWebUISwitchColors.trackOffDark else OpenWebUISwitchColors.trackOffLight,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(thumbSize)
                .background(color = thumbColor, shape = CircleShape)
        )
    }
}

/** Disabled-state placeholder used before the switch is attached. */
@Composable
fun OpenWebUISwitchPlaceholder(trackWidth: Dp = 28.dp, trackHeight: Dp = 16.dp) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val color = if (dark) OpenWebUISwitchColors.trackOffDark else OpenWebUISwitchColors.trackOffLight
    Box(
        modifier = Modifier
            .size(width = trackWidth, height = trackHeight)
            .background(color = color.copy(alpha = 0.4f), shape = CircleShape)
    )
}
