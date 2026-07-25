package ai.fatai.ai

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformMessageContextMenu(
    copyLabel: String,
    onCopy: () -> Unit,
    content: @Composable () -> Unit
) = content()
