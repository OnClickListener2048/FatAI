package ai.fatai.ai

import ai.fatai.ai.chat.ChatScreen
import androidx.compose.runtime.Composable

/**
 * Thin entry point for the chat screen. All UI lives in [ai.fatai.ai.chat] so the chat
 * feature can grow without inflating the navigation-facing class.
 */
class AIChatScreen {

    @Composable
    fun Content(onSettings: () -> Unit) {
        ChatScreen(onSettings = onSettings)
    }
}
