package ai.fatai.di

import ai.fatai.database.DatabaseDriverFactory
import ai.fatai.feature.model.CactusLocalModelEngine
import ai.fatai.feature.model.LocalModelDownloader
import ai.fatai.feature.model.LocalModelEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single {
        // ✨ 在这里创建 DatabaseDriverFactory 的 Android 实例 ✨
        // koin-android 提供的 androidContext() 会自动传入 Context
        DatabaseDriverFactory(androidContext())
    }
    // Embedded cactus runtime (arm64-v8a JNI). One concrete instance exposed under both the
    // engine interface (for the router) and the downloader capability (for the settings UI).
    single { CactusLocalModelEngine(androidContext(), get(), get()) }
    single<LocalModelEngine> { get<CactusLocalModelEngine>() }
    single<LocalModelDownloader> { get<CactusLocalModelEngine>() }
}