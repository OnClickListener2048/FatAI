package ai.fatai.di

import ai.fatai.database.DatabaseDriverFactory
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single {
        // ✨ 在这里创建 DatabaseDriverFactory 的 Android 实例 ✨
        // koin-android 提供的 androidContext() 会自动传入 Context
        DatabaseDriverFactory(androidContext())
    }
}