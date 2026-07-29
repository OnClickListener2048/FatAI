package ai.fatai

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ai.fatai.di.initKoin

fun main() {
    initKoin()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "FatAI",
        ) {
            App()
        }
    }
}
