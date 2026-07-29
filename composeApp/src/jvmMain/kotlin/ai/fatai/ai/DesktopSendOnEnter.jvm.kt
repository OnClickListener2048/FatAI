package ai.fatai.ai

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

actual fun Modifier.desktopSendOnEnter(enabled: Boolean, onSend: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (
            enabled &&
            event.type == KeyEventType.KeyDown &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
            !event.isShiftPressed
        ) {
            onSend()
            true
        } else {
            false
        }
    }
