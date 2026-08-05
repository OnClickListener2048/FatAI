package ai.fatai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ai.fatai.ai.AIChatScreen
import ai.fatai.ai.AISettingsScreen
import ai.fatai.di.initKoin
import ai.fatai.navigation.DefaultRootComponent
import ai.fatai.navigation.RootComponent
import ai.fatai.theme.FatAITheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
@OptIn(ExperimentalComposeUiApi::class)
fun App() {
    val root = remember { DefaultRootComponent(DefaultComponentContext(LifecycleRegistry())) }
    FatAITheme {
        Children(stack = root.childStack) { child ->
            when (child.instance) {
                RootComponent.Child.Chat -> AIChatScreen().Content(onSettings = root::openSettings)
                RootComponent.Child.Settings -> {
                    // The back gesture/button pops settings instead of closing the app. The
                    // Decompose root context has no platform back dispatcher attached, so
                    // handleBackButton is inert here.
                    androidx.compose.ui.backhandler.BackHandler { root.closeSettings() }
                    AISettingsScreen().Content(onBack = root::closeSettings)
                }
            }
        }
    }
}

fun initKoinApp() {
    // 初始化 Koin 应用程序模块
    initKoin {
        // 在这里可以添加其他 Koin 配置
    }
}
