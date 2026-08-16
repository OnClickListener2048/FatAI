package ai.fatai.di

import ai.fatai.database.DatabaseDriverFactory
import ai.fatai.feature.model.HttpLocalModelEngine
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
    // The local model is an OpenAI-compatible HTTP endpoint (cactus serve, Ollama, LM Studio...),
    // exactly like desktop and iOS — no embedded engine ships with the app.
    single<LocalModelEngine> { get<HttpLocalModelEngine>() }
}