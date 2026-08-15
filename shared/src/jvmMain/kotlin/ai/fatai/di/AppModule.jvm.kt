package ai.fatai.di

import ai.fatai.database.DatabaseDriverFactory
import ai.fatai.feature.model.HttpLocalModelEngine
import ai.fatai.feature.model.LocalModelEngine
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single {
        // ✨ 在这里创建 DatabaseDriverFactory 的 Desktop 实例 ✨
        println("DatabaseDriverFactory")
        DatabaseDriverFactory()
    }
    // Desktop has no embedded cactus build (ARM-only); the local model is always an
    // OpenAI-compatible HTTP endpoint (cactus serve on an ARM host, Ollama, LM Studio...).
    single<LocalModelEngine> { get<HttpLocalModelEngine>() }
}