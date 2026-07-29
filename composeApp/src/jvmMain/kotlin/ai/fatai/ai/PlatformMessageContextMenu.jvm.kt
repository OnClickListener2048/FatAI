package ai.fatai.ai

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformMessageContextMenu(
    enabled: Boolean,
    copyLabel: String,
    onCopy: () -> Unit,
    content: @Composable () -> Unit
) {
    if (enabled) {
        ContextMenuArea(items = { listOf(ContextMenuItem(copyLabel, onCopy)) }, content = content)
    } else {
        content()
    }
}
