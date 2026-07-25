package ai.fatai.ai

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformMessageContextMenu(
    copyLabel: String,
    onCopy: () -> Unit,
    content: @Composable () -> Unit
) {
    ContextMenuArea(items = { listOf(ContextMenuItem(copyLabel, onCopy)) }, content = content)
}
