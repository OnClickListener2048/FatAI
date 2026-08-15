package ai.fatai.di

import ai.fatai.database.DatabaseDriverFactory
import ai.fatai.feature.model.HttpLocalModelEngine
import ai.fatai.feature.model.LocalModelEngine
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single {
        // ✨ 在这里创建 DatabaseDriverFactory 的 iOS 实例 ✨
        DatabaseDriverFactory()
    }
    // The embedded cactus XCFramework integration needs a Mac; v1 routes iOS through the
    // shared OpenAI-compatible HTTP engine (same code as desktop).
    single<LocalModelEngine> { get<HttpLocalModelEngine>() }
}