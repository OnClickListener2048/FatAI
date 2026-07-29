package ai.fatai.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value

interface RootComponent {
    val childStack: Value<ChildStack<*, Child>>

    fun openSettings()
    fun closeSettings()

    sealed interface Child {
        data object Chat : Child
        data object Settings : Child
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext {
    private val navigation = StackNavigation<Configuration>()

    override val childStack: Value<ChildStack<Configuration, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = null,
        initialConfiguration = Configuration.Chat,
        handleBackButton = true,
        childFactory = { configuration, _ ->
            when (configuration) {
                Configuration.Chat -> RootComponent.Child.Chat
                Configuration.Settings -> RootComponent.Child.Settings
            }
        }
    )

    override fun openSettings() = navigation.pushNew(Configuration.Settings)

    override fun closeSettings() = navigation.pop()

    sealed interface Configuration {
        data object Chat : Configuration
        data object Settings : Configuration
    }
}
