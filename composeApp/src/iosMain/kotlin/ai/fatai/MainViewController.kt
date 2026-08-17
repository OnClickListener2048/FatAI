package ai.fatai

import ai.fatai.di.initKoin
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    // Android 在 MainActivity、桌面在 main() 中初始化 Koin;iOS 入口此前漏掉了这一步,
    // 导致 FatAITheme 里的 currentKoinScope 抛 "KoinApplication has not been started"。
    initKoin()
    return ComposeUIViewController { App() }
}
