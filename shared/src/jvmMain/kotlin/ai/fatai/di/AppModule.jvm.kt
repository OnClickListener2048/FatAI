package ai.fatai.di

import ai.fatai.database.DatabaseDriverFactory
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single {
        // ✨ 在这里创建 DatabaseDriverFactory 的 Desktop 实例 ✨
        println("DatabaseDriverFactory")
        DatabaseDriverFactory()
    }
}