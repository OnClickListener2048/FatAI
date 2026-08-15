package ai.fatai.di

import ai.fatai.chat.OpenAICompatibleProvider
import ai.fatai.chat.ProviderType
import ai.fatai.database.Database
import ai.fatai.network.provideHttpClient
import ai.fatai.repo.ChatRepository
import ai.fatai.repo.ApiKeyRepository
import ai.fatai.feature.files.FileAssetRepository
import ai.fatai.feature.files.FileAssetService
import ai.fatai.feature.memory.MemoryRepository
import ai.fatai.feature.memory.ConversationMemoryService
import ai.fatai.feature.memory.UserMemoryExtractionService
import ai.fatai.feature.model.FatAiServerModelGateway
import ai.fatai.feature.model.FatAiServerSync
import ai.fatai.feature.model.HttpLocalModelEngine
import ai.fatai.feature.model.LocalFirstRouterGateway
import ai.fatai.feature.model.ModelGateway
import ai.fatai.feature.model.SyncOutboxStore
import ai.fatai.feature.model.SyncRemoteStore
import ai.fatai.sync.SyncMutationSink
import ai.fatai.feature.prompt.PromptTemplateRepository
import ai.fatai.feature.workspace.WorkspaceRepository
import ai.fatai.feature.settings.SettingsRepository
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.feature.user.UserRepository
import ai.fatai.feature.tools.DefaultTools
import ai.fatai.feature.tools.DefaultToolProviderAdapters
import ai.fatai.feature.tools.DoclingDocumentTool
import ai.fatai.feature.tools.ToolProviderAdapterRegistry
import ai.fatai.feature.tools.ToolExecutionPolicy
import ai.fatai.feature.tools.ToolRegistry
import org.koin.core.module.Module
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
        ChatRepository(get(), get(), get())
    }

    single {
        println("ApiKeyRepository")
        ApiKeyRepository(get(), get(), get())
    }

    single { WorkspaceRepository(get(), get(), get()) }
    single { MemoryRepository(get(), get(), get()) }
    single { PromptTemplateRepository(get(), get(), get()) }
    single { FileAssetRepository(get(), get()) }
    single { SettingsRepository(get(), get()) }
    single { SyncOutboxStore(get(), get()) }
    single { SyncRemoteStore(get(), get()) }
    single { FileAssetService(get(), accessToken = { get<FatAiServerSync>().accessToken() }) }
    single { DoclingDocumentTool(get(), accessTokenProvider = { get<FatAiServerSync>().accessToken() }) }
    // Docling conversion can produce richer Markdown than lightweight tools, while the registry
    // still imposes a strict prompt-sized bound on every tool result.
    single { ToolRegistry(DefaultTools.all(get(), get<DoclingDocumentTool>()), ToolExecutionPolicy(maxOutputCharacters = 24_000)) }
    single { ToolProviderAdapterRegistry(DefaultToolProviderAdapters.all()) }

    single { FatAiServerModelGateway(get(), get()) }
    single { OpenAICompatibleProvider(ProviderType.Custom, get()) }
    single { HttpLocalModelEngine(get(), get()) }
    // Chat turns (localRoute NONE) always reach the cloud gateway; lightweight tasks are
    // dispatched by the router to the platform-registered LocalModelEngine.
    single<ModelGateway> { LocalFirstRouterGateway(get(), get()) }
    single { FatAiServerSync(get(), get(), get(), get(), get()) }
    single<SyncMutationSink> { get<FatAiServerSync>() }
    single { ConversationMemoryService(get(), get()) }
    single { UserMemoryExtractionService(get(), get()) }
}

expect fun platformModule(): Module
