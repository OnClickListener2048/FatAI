package ai.fatai.di

import ai.fatai.database.Database
import ai.fatai.network.provideHttpClient
import ai.fatai.repo.ChatRepository
import ai.fatai.repo.ApiKeyRepository
import ai.fatai.chat.OpenAICompatibleProvider
import ai.fatai.chat.ChatProvider
import ai.fatai.chat.ProviderType
import ai.fatai.core.context.ContextEngine
import ai.fatai.core.context.FilePromptProvider
import ai.fatai.core.context.HistoryPromptProvider
import ai.fatai.core.context.MemoryPromptProvider
import ai.fatai.core.context.SystemPromptProvider
import ai.fatai.core.context.TemplatePromptProvider
import ai.fatai.core.context.WorkspacePromptProvider
import ai.fatai.feature.files.FileAssetRepository
import ai.fatai.feature.memory.MemoryRepository
import ai.fatai.feature.memory.ConversationMemoryService
import ai.fatai.feature.memory.UserMemoryExtractionService
import ai.fatai.feature.model.ChatProviderModelGateway
import ai.fatai.feature.model.ModelGateway
import ai.fatai.feature.prompt.PromptTemplateRepository
import ai.fatai.feature.workspace.WorkspaceRepository
import ai.fatai.feature.settings.SettingsRepository
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.feature.user.UserRepository
import ai.fatai.feature.tools.DefaultTools
import ai.fatai.feature.tools.DefaultToolProviderAdapters
import ai.fatai.feature.tools.ToolProviderAdapterRegistry
import ai.fatai.feature.tools.ToolRegistry
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val sharedModule = module {
    println("sharedModule")

    single {
        println("HttpClient")
        provideHttpClient()
    }

    single {
        println("Database")
        Database(get())
    }

    single {
        println("WatsonQueries")
        get<Database>().watsonQueries
    }

    single { UserRepository(get()) }
    single<CurrentUserProvider> { get<UserRepository>() }

    single {
        println("ChatRepository")
        ChatRepository(get(), get())
    }

    single {
        println("ApiKeyRepository")
        ApiKeyRepository(get(), get())
    }

    single { WorkspaceRepository(get(), get()) }
    single { MemoryRepository(get(), get()) }
    single { PromptTemplateRepository(get(), get()) }
    single { FileAssetRepository(get(), get()) }
    single { SettingsRepository(get(), get()) }
    single { ToolRegistry(DefaultTools.all(get())) }
    single { ToolProviderAdapterRegistry(DefaultToolProviderAdapters.all()) }

    single<ChatProvider> {
        println("OpenAICompatibleProvider")
        OpenAICompatibleProvider(ProviderType.OpenAI, get())
    }

    single<ModelGateway> { ChatProviderModelGateway(get()) }
    single { ConversationMemoryService(get(), get()) }
    single { UserMemoryExtractionService(get(), get()) }

    single {
        ContextEngine(
            setOf(
                SystemPromptProvider(),
                TemplatePromptProvider(get()),
                WorkspacePromptProvider(),
                MemoryPromptProvider(get()),
                FilePromptProvider(get()),
                HistoryPromptProvider()
            )
        )
    }
}

expect fun platformModule(): Module

fun initKoin2(appDeclaration: KoinAppDeclaration = {}) {
    println("initKoin")
    startKoin {
        printLogger(Level.DEBUG)
        modules(sharedModule, platformModule())
        appDeclaration()
    }
}
