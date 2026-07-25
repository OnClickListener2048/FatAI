package ai.fatai.ai

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformMessageContextMenu(
    enabled: Boolean,
    copyLabel: String,
    onCopy: () -> Unit,
    content: @Composable () -> Unit
)
