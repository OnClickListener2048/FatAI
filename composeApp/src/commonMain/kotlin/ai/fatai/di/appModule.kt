package ai.fatai.di

import ai.fatai.viewmodel.AIChatViewModel
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val appModule = module {
    println("appModule")
    single {
        println("AIChatViewModel")
        AIChatViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    println("initKoin")
    // MainActivity can be recreated without a process restart (back to the app, rotation,
    // reinstall), which would otherwise crash on the second startKoin call.
    if (org.koin.core.context.GlobalContext.getOrNull() != null) return
    startKoin {
        printLogger(Level.DEBUG)
        modules(sharedModule, appModule, platformModule())
        appDeclaration()
    }
}
