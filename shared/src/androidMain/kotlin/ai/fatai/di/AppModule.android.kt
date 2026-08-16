package ai.fatai.di

import ai.fatai.database.DatabaseDriverFactory
import ai.fatai.feature.model.LocalModelEngine
import ai.fatai.feature.model.NeedleLocalModelEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single {
        // ✨ 在这里创建 DatabaseDriverFactory 的 Android 实例 ✨
        // koin-android 提供的 androidContext() 会自动传入 Context
        DatabaseDriverFactory(androidContext())
    }
    // Embedded needle2 engine (arm64-v8a JNI, bundled needle2.cact asset). The model ships
    // with the app, so no download capability is registered — the settings UI hides those
    // controls; x86_64 emulators fall back to the cloud via the router.
    single { NeedleLocalModelEngine(androidContext(), get(), get()) }
    single<LocalModelEngine> { get<NeedleLocalModelEngine>() }
}