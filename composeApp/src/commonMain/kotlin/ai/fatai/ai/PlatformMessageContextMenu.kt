package ai.fatai.ai

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformMessageContextMenu(
    copyLabel: String,
    onCopy: () -> Unit,
    content: @Composable () -> Unit
)
