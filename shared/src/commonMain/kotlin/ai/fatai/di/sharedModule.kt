package ai.fatai.di

import ai.fatai.database.Database
import ai.fatai.network.provideHttpClient
import ai.fatai.repo.ChatRepository
import ai.fatai.repo.ApiKeyRepository
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
import ai.fatai.feature.model.FatAiServerModelGateway
import ai.fatai.feature.model.FatAiServerSync
import ai.fatai.feature.model.ModelGateway
import ai.fatai.feature.model.SyncOutboxStore
import ai.fatai.feature.model.SyncRemoteStore
import ai.fatai.feature.prompt.PromptTemplateRepository
import ai.fatai.feature.workspace.WorkspaceRepository
import ai.fatai.feature.settings.SettingsRepository
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.feature.user.UserRepository
import ai.fatai.feature.tools.DefaultTools
import ai.fatai.feature.tools.DefaultToolProviderAdapters
import ai.fatai.feature.tools.ToolProviderAdapterRegistry
import ai.fatai.feature.tools.ToolExecutionPolicy
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
        ApiKeyRepository(get(), get(), get())
    }

    single { WorkspaceRepository(get(), get()) }
    single { MemoryRepository(get(), get(), get()) }
    single { PromptTemplateRepository(get(), get(), get()) }
    single { FileAssetRepository(get(), get()) }
    single { SettingsRepository(get(), get()) }
    single { SyncOutboxStore(get(), get()) }
    single { SyncRemoteStore(get(), get()) }
    // Docling conversion can produce richer Markdown than lightweight tools, while the registry
    // still imposes a strict prompt-sized bound on every tool result.
    single { ToolRegistry(DefaultTools.all(get()), ToolExecutionPolicy(maxOutputCharacters = 24_000)) }
    single { ToolProviderAdapterRegistry(DefaultToolProviderAdapters.all()) }

    single<ModelGateway> { FatAiServerModelGateway(get(), get()) }
    single { FatAiServerSync(get(), get(), get(), get(), get()) }
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
