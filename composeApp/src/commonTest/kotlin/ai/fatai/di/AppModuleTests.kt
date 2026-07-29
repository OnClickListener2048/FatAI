package ai.fatai

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class AppModuleTests {

    @Test
    fun startingKoinWithAppModuleInitializesGlobalContext() {
        try { org.koin.core.context.GlobalContext.stopKoin() } catch (_: Exception) {}
        startKoin { modules(appModule) }
        val ctx = GlobalContext.get()
        assertNotNull(ctx)
        org.koin.core.context.GlobalContext.stopKoin()
    }

    @Test
    fun gettingKoinBeforeInitializationThrowsIllegalStateException() {
        try { org.koin.core.context.GlobalContext.stopKoin() } catch (_: Exception) {}
        assertFailsWith<IllegalStateException> {
            GlobalContext.get()
        }
    }

    @Test
    fun requestingChatViewModelWithoutProvidedDependenciesThrowsException() {
        try { org.koin.core.context.GlobalContext.stopKoin() } catch (_: Exception) {}
        startKoin { modules(appModule) }
        val koin = GlobalContext.get().koin
        assertFailsWith<Exception> {
            koin.get<ai.fatai.viewmodel.ChatViewModel>()
        }
        org.koin.core.context.GlobalContext.stopKoin()
    }
}
