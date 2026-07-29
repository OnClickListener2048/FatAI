package ai.fatai.ai

import androidx.compose.ui.Modifier

expect fun Modifier.desktopSendOnEnter(enabled: Boolean, onSend: () -> Unit): Modifier
