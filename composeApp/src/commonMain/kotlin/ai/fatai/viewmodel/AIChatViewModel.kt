package ai.fatai.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatStreamChunk
import ai.fatai.chat.ProviderConfig
import ai.fatai.chat.ProviderType
import ai.fatai.core.locale.currentLanguageTag
import ai.fatai.feature.model.ModelGateway
import ai.fatai.feature.model.ChatContext
import ai.fatai.feature.model.DEFAULT_FAT_AI_SERVER_URL
import ai.fatai.feature.model.FatAiServerSync
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetRepository
import ai.fatai.feature.memory.ConversationMemoryService
import ai.fatai.feature.memory.UserMemoryExtractionService
import ai.fatai.feature.tools.ToolCall
import ai.fatai.feature.tools.ToolRegistry
import ai.fatai.feature.tools.ToolResult
import ai.fatai.feature.workspace.INBOX_WORKSPACE_ID
import ai.fatai.feature.workspace.Workspace
import ai.fatai.feature.workspace.WorkspaceRepository
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.repo.ChatItem
import ai.fatai.repo.ChatRepository
import ai.fatai.repo.Conversation
import ai.fatai.repo.MessageSource
import ai.fatai.repo.ApiKeyRepository
import ai.fatai.repo.ApiKeyInfo
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.Clock

data class ChatScreenState(
    val conversations: List<Conversation> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val currentWorkspaceId: String = INBOX_WORKSPACE_ID,
    val attachments: List<FileAsset> = emptyList(),
    val messageAttachments: Map<String, List<FileAsset>> = emptyMap(),
    val messages: List<ChatItem> = emptyList(),
    val currentConversationId: String? = null,
    val chatScrollPosition: ChatScrollPosition = ChatScrollPosition(),
    val isStreaming: Boolean = false,
    val assistantActivity: AssistantActivity? = null,
    val isLoading: Boolean = false,
    val inputText: String = "",
    val activeProvider: ProviderType = ProviderType.OpenAI,
    val activeConfig: ProviderConfig? = null
)

data class ChatScrollPosition(
    val conversationId: String? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val hasSavedPosition: Boolean = false
)

enum class AssistantActivity { Thinking, Searching, CheckingWeather, UsingTool }

class AIChatViewModel(
    private val chatRepository: ChatRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val modelGateway: ModelGateway,
    private val workspaceRepository: WorkspaceRepository,
    private val fileAssetRepository: FileAssetRepository,
    private val conversationMemoryService: ConversationMemoryService,
    private val userMemoryExtractionService: UserMemoryExtractionService,
    private val toolRegistry: ToolRegistry,
    private val currentUser: CurrentUserProvider,
    private val serverSync: FatAiServerSync
) {

    private val screenModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(ChatScreenState())
    val state: StateFlow<ChatScreenState> = _state.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private var streamJob: Job? = null
    private var shouldStopStream = false

    init {
        refresh()
        // Remote changes (e.g. the server-generated conversation title) land in the local DB
        // from the background pull; reload the visible lists so the UI stays current.
        screenModelScope.launch {
            serverSync.remoteChangesApplied.collect {
                loadConversations()
                val current = _state.value.currentConversationId
                // Skip the message reload while streaming: the in-progress assistant message
                // exists only in memory and would be wiped by a DB re-read.
                if (current != null && !_state.value.isStreaming) {
                    selectConversation(current)
                }
            }
        }
    }

    /**
     * Rehydrates the chat screen from persistent storage.
     *
     * The desktop screen can be recreated while this Koin singleton remains alive, so loading
     * only during construction is not enough to guarantee that saved conversations reappear.
     */
    fun refresh() {
        val inbox = workspaceRepository.ensureInbox()
        val workspaceId = _state.value.currentWorkspaceId
            .takeIf { workspaceRepository.getById(it) != null }
            ?: inbox.id
        val conversations = chatRepository.getConversations(workspaceId)
        val selectedConversationId = _state.value.currentConversationId
            ?.takeIf { selectedId -> conversations.any { it.id == selectedId } }
            ?: conversations.maxByOrNull { it.updatedAt }?.id

        _state.value = _state.value.copy(
            conversations = conversations,
            workspaces = workspaceRepository.getAll(),
            currentWorkspaceId = workspaceId,
            currentConversationId = selectedConversationId,
            messages = emptyList(),
            attachments = emptyList(),
            messageAttachments = emptyMap(),
            inputText = ""
        )
        loadActiveConfig()
        selectedConversationId?.let(::selectConversation)
    }    fun loadConversations() {
        val conversations = chatRepository.getConversations(_state.value.currentWorkspaceId)
        _state.value = _state.value.copy(conversations = conversations)
    }

    fun loadWorkspaces() {
        _state.value = _state.value.copy(workspaces = workspaceRepository.getAll())
    }

    fun selectWorkspace(workspaceId: String) {
        if (workspaceRepository.getById(workspaceId) == null) return
        _state.value = _state.value.copy(
            currentWorkspaceId = workspaceId,
            currentConversationId = null,
            messages = emptyList(),
            attachments = emptyList(),
            messageAttachments = emptyMap(),
            inputText = ""
        )
        loadConversations()
    }

    fun createWorkspace(name: String, systemPrompt: String = "") {
        if (name.isBlank()) return
        val workspace = workspaceRepository.create(name, systemPrompt)
        serverSync.syncWorkspace(workspace.id, workspace.name, workspace.systemPrompt)
        loadWorkspaces()
        selectWorkspace(workspace.id)
    }

    fun loadArchivedConversations(): List<Conversation> {
        return chatRepository.getArchivedConversations()
    }

    fun searchConversations(query: String) {
        if (query.isBlank()) {
            loadConversations()
        } else {
            val results = chatRepository.searchConversations(query)
            _state.value = _state.value.copy(conversations = results)
        }
    }

    private fun loadActiveConfig() {
        val activeKey = apiKeyRepository.getActiveKey()
        _state.value = _state.value.copy(
            activeProvider = activeKey?.providerType ?: ProviderType.OpenAI,
            activeConfig = activeKey?.let { key ->
                ProviderConfig(
                    apiKey = key.apiKey,
                    baseUrl = key.baseUrl,
                    model = key.model.ifBlank { key.providerType.defaultModel },
                    configurationId = key.id,
                    configurationName = key.name,
                    providerType = key.providerType
                )
            }
        )
    }

    fun updateInputText(text: String) {
        _state.value = _state.value.copy(inputText = text)
    }

    fun setActiveApiKey(keyInfo: ApiKeyInfo) {
        apiKeyRepository.setActiveKey(keyInfo.id)
        _state.value = _state.value.copy(
            activeProvider = keyInfo.providerType,
            activeConfig = ProviderConfig(
                apiKey = keyInfo.apiKey,
                baseUrl = keyInfo.baseUrl,
                model = keyInfo.model.ifBlank { keyInfo.providerType.defaultModel },
                configurationId = keyInfo.id,
                configurationName = keyInfo.name,
                providerType = keyInfo.providerType
            )
        )
    }

    fun newConversation() {
        loadActiveConfig()
        val config = _state.value.activeConfig ?: run {
            screenModelScope.launch { _toastEvents.emit("Please configure an API key first") }
            return
        }
        val provider = _state.value.activeProvider
        val conversation = chatRepository.createConversation(
            workspaceId = _state.value.currentWorkspaceId,
            providerType = provider,
            model = config.model
        )
        serverSync.syncConversation(
            id = conversation.id,
            workspaceId = conversation.workspaceId,
            title = conversation.title,
            providerType = conversation.providerType.name,
            model = conversation.model
        )
        _state.value = _state.value.copy(
            currentConversationId = conversation.id,
            messages = emptyList(),
            attachments = emptyList(),
            messageAttachments = emptyMap(),
            inputText = ""
        )
        loadConversations()
    }

    fun selectConversation(conversationId: String) {
        val messages = chatRepository.getMessages(conversationId)
        val assets = fileAssetRepository.forConversation(conversationId)
        val scrollPosition = _state.value.chatScrollPosition
        _state.value = _state.value.copy(
            currentConversationId = conversationId,
            messages = messages,
            chatScrollPosition = scrollPosition.takeIf { it.conversationId == conversationId }
                ?: ChatScrollPosition(conversationId = conversationId),
            attachments = assets.filter { it.messageId == null },
            messageAttachments = assets
                .filter { it.messageId != null }
                .groupBy { it.messageId!! },
            inputText = ""
        )
    }

    fun updateChatScrollPosition(conversationId: String, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val current = _state.value
        if (current.currentConversationId != conversationId) return
        val position = current.chatScrollPosition
        if (
            position.conversationId == conversationId &&
            position.firstVisibleItemIndex == firstVisibleItemIndex &&
            position.firstVisibleItemScrollOffset == firstVisibleItemScrollOffset
        ) return

        _state.value = current.copy(
            chatScrollPosition = ChatScrollPosition(
                conversationId = conversationId,
                firstVisibleItemIndex = firstVisibleItemIndex,
                firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
                hasSavedPosition = true
            )
        )
    }

    fun attachFile(displayName: String, mimeType: String, localPath: String, sizeBytes: Long) {
        val conversationId = _state.value.currentConversationId ?: run {
            newConversation()
            _state.value.currentConversationId
        } ?: return
        fileAssetRepository.attach(
            displayName = displayName,
            mimeType = mimeType,
            localPath = localPath,
            sizeBytes = sizeBytes,
            workspaceId = _state.value.currentWorkspaceId,
            conversationId = conversationId
        )
        _state.value = _state.value.copy(attachments = fileAssetRepository.pendingForConversation(conversationId))
    }

    fun removeAttachment(id: String) {
        fileAssetRepository.delete(id)
        val conversationId = _state.value.currentConversationId ?: return
        _state.value = _state.value.copy(attachments = fileAssetRepository.pendingForConversation(conversationId))
    }

    fun sendMessage(analyzeAttachedFilePrompt: String) {
        val text = _state.value.inputText.trim()
        val pendingAttachments = _state.value.attachments
        if ((text.isBlank() && pendingAttachments.isEmpty()) || _state.value.isStreaming) return

        val config = _state.value.activeConfig ?: run {
            screenModelScope.launch { _toastEvents.emit("Please configure an API key first") }
            return
        }

        var conversationId = _state.value.currentConversationId
        if (conversationId == null) {
            val conversation = chatRepository.createConversation(
                workspaceId = _state.value.currentWorkspaceId,
                providerType = _state.value.activeProvider,
                model = config.model
            )
            conversationId = conversation.id
            serverSync.syncConversation(
                id = conversation.id,
                workspaceId = conversation.workspaceId,
                title = conversation.title,
                providerType = conversation.providerType.name,
                model = conversation.model
            )
        }

        // The server persists this turn during the chat stream; local insert only.
        // If the stream fails, the catch block enqueues the question as a fallback.
        val userMsg = chatRepository.insertMessage(
            conversationId = conversationId,
            content = text.ifBlank { analyzeAttachedFilePrompt },
            type = ChatItemType.Question,
            contentType = MessageContentType.Text,
            sync = false
        )
        screenModelScope.launch {
            userMemoryExtractionService.rememberFromUserInput(userMsg.content, config)
        }
        fileAssetRepository.assignPendingToMessage(conversationId, userMsg.id)
        val messages = _state.value.messages + userMsg
        _state.value = _state.value.copy(
            currentConversationId = conversationId,
            inputText = "",
            messages = messages,
            attachments = emptyList(),
            messageAttachments = _state.value.messageAttachments + (userMsg.id to pendingAttachments)
        )
        loadConversations()
        streamChat(conversationId, messages, pendingAttachments)
    }

    private fun streamChat(
        conversationId: String,
        messages: List<ChatItem>,
        attachments: List<FileAsset> = emptyList(),
        replaceMessageId: String? = null
    ) {
        val config = _state.value.activeConfig ?: return
        @OptIn(ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)
        var assistantMsg = ChatItem(
            id = Uuid.random().toString(),
            userId = currentUser.currentUserId,
            conversationId = conversationId,
            content = "",
            type = ChatItemType.Answer,
            contentType = MessageContentType.Markdown,
            createdAt = Clock.System.now().toEpochMilliseconds(),
            isLoading = true
        )

        _state.value = _state.value.copy(
            messages = messages + assistantMsg,
            isStreaming = true,
            assistantActivity = AssistantActivity.Thinking
        )

        shouldStopStream = false
        streamJob = screenModelScope.launch {
            try {
                val history = messages
                    .filter { !it.isLoading && !it.content.startsWith("Error:") }
                    .map {
                    ChatMessage(role = if (it.type == ChatItemType.Question) "user" else "assistant", content = it.content)
                }

                val isAttachmentAnalysis = attachments.isNotEmpty()
                val documentExecutions = if (attachments.isEmpty()) {
                    emptyList()
                } else {
                    _state.value = _state.value.copy(assistantActivity = AssistantActivity.UsingTool)
                    attachments.map { attachment ->
                        toolRegistry.execute(
                            ToolCall(
                                "docling_document_read",
                                mapOf(
                                    "local_path" to attachment.localPath,
                                    "display_name" to attachment.displayName,
                                    "mime_type" to attachment.mimeType
                                )
                            )
                        )
                    }.also {
                        _state.value = _state.value.copy(assistantActivity = AssistantActivity.Thinking)
                    }
                }
                val toolResults = documentExecutions.map { execution -> formatToolResult(execution) }
                val prompt = if (isAttachmentAnalysis) history.takeLast(1) else history
                val context = ChatContext(
                    workspaceId = _state.value.currentWorkspaceId,
                    conversationId = conversationId,
                    responseLanguageTag = currentLanguageTag(),
                    toolResults = toolResults,
                    includeContextualReferences = !isAttachmentAnalysis,
                    userMessageId = messages.lastOrNull { it.type == ChatItemType.Question }?.id,
                    assistantMessageId = assistantMsg.id
                )
                val toolCalls = collectModelResponse(
                    prompt = prompt,
                    config = config,
                    context = context,
                    includeTools = true,
                    onContent = { content ->
                    if (content.isNotEmpty()) {
                        assistantMsg = assistantMsg.copy(
                            content = assistantMsg.content + content,
                            isLoading = false
                        )
                        updateMessageInState(assistantMsg)
                    }
                    },
                    onReasoning = { reasoningContent ->
                    if (reasoningContent.isNotEmpty()) {
                        assistantMsg = assistantMsg.copy(
                            reasoningContent = assistantMsg.reasoningContent + reasoningContent,
                            isLoading = false
                        )
                        updateMessageInState(assistantMsg)
                    }
                    },
                    onToolCalls = { calls ->
                        if (calls.isNotEmpty()) _state.value = _state.value.copy(assistantActivity = calls.activity())
                    }
                )
                if (!shouldStopStream) {
                    val serverToolExecutions = toolCalls.map { call ->
                        ai.fatai.feature.tools.ToolExecution(
                            call = ToolCall(call.name, call.arguments),
                            // The server executes the tool and returns structured sources.
                            result = ToolResult.Success("", sources = call.sources)
                        )
                    }
                    val referencedExecutions = documentExecutions + serverToolExecutions
                    if (referencedExecutions.isNotEmpty()) {
                        assistantMsg = assistantMsg.withToolSources(referencedExecutions)
                        updateMessageInState(assistantMsg)
                    }
                    completeAssistantResponse(conversationId, messages, assistantMsg, config)
                    // The replacement succeeded; the regenerated-away answer can now go.
                    replaceMessageId?.let { oldMessageId ->
                        if (oldMessageId != assistantMsg.id) chatRepository.deleteMessage(oldMessageId)
                    }
                }
            } catch (e: CancellationException) {
                // The user stopped generation; stopGeneration() keeps the partial answer.
                throw e
            } catch (e: Exception) {
                assistantMsg = assistantMsg.copy(
                    content = "Error: ${e.message}",
                    isLoading = false
                )
                updateMessageInState(assistantMsg)
                _state.value = _state.value.copy(isStreaming = false, assistantActivity = null)
                // The server persists chat turns only when the stream completes; enqueue the
                // question so it still reaches the server when the model call failed.
                messages.lastOrNull { it.type == ChatItemType.Question }?.let { question ->
                    serverSync.syncMessage(
                        id = question.id,
                        conversationId = question.conversationId,
                        role = "user",
                        content = question.content,
                        contentType = question.contentType.name
                    )
                }
            }
        }
    }

    private suspend fun collectModelResponse(
        prompt: List<ChatMessage>,
        config: ProviderConfig,
        context: ChatContext = ChatContext(),
        includeTools: Boolean,
        onContent: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        onToolCalls: (List<ai.fatai.feature.tools.ProviderToolCall>) -> Unit = {}
    ): List<ai.fatai.feature.tools.ProviderToolCall> {
        var toolCalls = emptyList<ai.fatai.feature.tools.ProviderToolCall>()
        var lastRenderedAt = 0L
        modelGateway.stream(
            messages = prompt,
            config = config,
            tools = if (includeTools) toolRegistry.definitions() else emptyList(),
            context = context
        ).collect { chunk ->
            if (shouldStopStream) return@collect
            if (chunk.reasoningContent.isNotEmpty()) {
                lastRenderedAt = awaitNextStreamFrame(lastRenderedAt)
                onReasoning(chunk.reasoningContent)
            }
            if (chunk.content.isNotEmpty()) {
                // A fast provider (or a buffered transport) can make several chunks available
                // in one main-thread turn. StateFlow keeps the latest value in that case, so
                // Compose gets no opportunity to draw the intermediate text. Limit commits to
                // the display frame rate and yield between them.
                lastRenderedAt = awaitNextStreamFrame(lastRenderedAt)
                onContent(chunk.content)
            }
            if (chunk.toolCalls.isNotEmpty()) {
                toolCalls = toolCalls + chunk.toolCalls
                onToolCalls(toolCalls)
            }
            if (chunk.isDone) toolCalls = chunk.toolCalls
        }
        return toolCalls
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun awaitNextStreamFrame(lastRenderedAt: Long): Long {
        if (lastRenderedAt != 0L) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - lastRenderedAt
            val remaining = STREAM_RENDER_INTERVAL_MILLIS - elapsed
            if (remaining > 0) delay(remaining)
        }
        return Clock.System.now().toEpochMilliseconds()
    }

    private fun formatToolResult(execution: ai.fatai.feature.tools.ToolExecution): String = buildString {
        appendLine("Tool: ${execution.call.toolName}")
        when (val result = execution.result) {
            is ToolResult.Success -> appendLine(result.content)
            is ToolResult.Failure -> appendLine("Tool failed (${result.code}): ${result.message}")
        }
    }.trimEnd()

    private fun List<ai.fatai.feature.tools.ProviderToolCall>.activity(): AssistantActivity = when {
        any { it.name == "weather" } -> AssistantActivity.CheckingWeather
        any { it.name == "web_search" } -> AssistantActivity.Searching
        else -> AssistantActivity.UsingTool
    }

    private fun ChatItem.withToolSources(executions: List<ai.fatai.feature.tools.ToolExecution>): ChatItem {
        val sources = executions
            .flatMap { execution -> (execution.result as? ToolResult.Success)?.sources.orEmpty() }
            .distinctBy { source -> source.url ?: source.label }
            .map { source -> MessageSource(label = source.label, url = source.url) }
        if (sources.isEmpty()) return this
        return copy(sources = sources)
    }

    private fun completeAssistantResponse(
        conversationId: String,
        messages: List<ChatItem>,
        assistantMsg: ChatItem,
        config: ProviderConfig
    ) {
        // The server persisted this turn during the stream; only the local cache is written here.
        chatRepository.insertMessage(
            conversationId,
            assistantMsg.content,
            ChatItemType.Answer,
            id = assistantMsg.id,
            sync = false,
            sources = assistantMsg.sources
        )
        // The server generates a model-based title for new conversations and syncs it
        // back through the change stream; nothing to update locally here.
        _state.value = _state.value.copy(isStreaming = false, assistantActivity = null)
        loadConversations()
        screenModelScope.launch {
            conversationMemoryService.summarizeIfNeeded(
                workspaceId = chatRepository.getConversationById(conversationId)?.workspaceId ?: INBOX_WORKSPACE_ID,
                conversationId = conversationId,
                messages = (messages + assistantMsg).map {
                    ChatMessage(
                        role = if (it.type == ChatItemType.Question) "user" else "assistant",
                        content = it.content
                    )
                },
                config = config
            )
        }
    }

    fun stopGeneration() {
        shouldStopStream = true
        streamJob?.cancel()
        val messages = _state.value.messages.map {
            if (it.isLoading) it.copy(isLoading = false) else it
        }
        _state.value = _state.value.copy(messages = messages, isStreaming = false, assistantActivity = null)

        val convId = _state.value.currentConversationId ?: return
        val lastAssistant = messages.lastOrNull { it.type == ChatItemType.Answer }
        if (lastAssistant != null && lastAssistant.content.isNotBlank()) {
            // The server saves the partial answer on disconnect with the same id.
            chatRepository.insertMessage(
                convId,
                lastAssistant.content,
                ChatItemType.Answer,
                id = lastAssistant.id,
                sync = false
            )
        }
    }

    fun regenerate() {
        val convId = _state.value.currentConversationId ?: return
        val msgs = _state.value.messages
        if (msgs.isEmpty()) return

        val lastAssistant = msgs.lastOrNull { it.type == ChatItemType.Answer }
        val lastQuestion = msgs.lastOrNull { it.type == ChatItemType.Question } ?: return

        // Keep the old answer visible and persisted until the replacement succeeds; a failed
        // stream must never leave the conversation without an answer.
        _state.value = _state.value.copy(
            messages = msgs,
            isLoading = false
        )
        streamChat(
            convId,
            msgs,
            _state.value.messageAttachments[lastQuestion.id].orEmpty(),
            replaceMessageId = lastAssistant?.id
        )
    }

    fun continueGeneration() {
        val config = _state.value.activeConfig ?: return
        val messages = _state.value.messages
        if (messages.isEmpty() || !_state.value.isStreaming) {
            val lastAssistant = _state.value.messages.lastOrNull { it.type == ChatItemType.Answer }
            if (lastAssistant != null) {
                val convId = _state.value.currentConversationId ?: return
                @OptIn(ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)
                var assistantMsg = ChatItem(
                    id = Uuid.random().toString(),
                    userId = currentUser.currentUserId,
                    conversationId = convId,
                    content = "",
                    type = ChatItemType.Answer,
                    contentType = MessageContentType.Markdown,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    isLoading = true
                )
                _state.value = _state.value.copy(
                    messages = _state.value.messages + assistantMsg,
                    isStreaming = true,
                    assistantActivity = AssistantActivity.Thinking
                )

                shouldStopStream = false
                streamJob = screenModelScope.launch {
                    try {
                        var streamCompleted = false
                        var lastRenderedAt = 0L
                        val history = _state.value.messages
                            .filter { !it.isLoading && !it.content.startsWith("Error:") }
                            .map {
                            ChatMessage(role = if (it.type == ChatItemType.Question) "user" else "assistant", content = it.content)
                        } + ChatMessage(role = "user", content = "Please continue from where you left off.")

                val context = ChatContext(
                    workspaceId = _state.value.currentWorkspaceId,
                    conversationId = convId,
                    responseLanguageTag = currentLanguageTag(),
                    assistantMessageId = assistantMsg.id
                )
                        modelGateway.stream(history, config, context = context).collect { chunk ->
                            if (shouldStopStream || streamCompleted) return@collect
                            if (chunk.reasoningContent.isNotEmpty()) {
                                lastRenderedAt = awaitNextStreamFrame(lastRenderedAt)
                                assistantMsg = assistantMsg.copy(
                                    reasoningContent = assistantMsg.reasoningContent + chunk.reasoningContent,
                                    isLoading = false
                                )
                                updateMessageInState(assistantMsg)
                            }
                            if (chunk.content.isNotEmpty()) {
                                lastRenderedAt = awaitNextStreamFrame(lastRenderedAt)
                                assistantMsg = assistantMsg.copy(
                                    content = assistantMsg.content + chunk.content,
                                    isLoading = false
                                )
                                updateMessageInState(assistantMsg)
                            }
                            if (chunk.isDone) {
                                streamCompleted = true
                                // The server persisted this answer during the stream.
                                chatRepository.insertMessage(
                                    convId,
                                    assistantMsg.content,
                                    ChatItemType.Answer,
                                    id = assistantMsg.id,
                                    sync = false
                                )
                                _state.value = _state.value.copy(isStreaming = false, assistantActivity = null)
                                loadConversations()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        assistantMsg = assistantMsg.copy(
                            content = "Error: ${e.message}",
                            isLoading = false
                        )
                        updateMessageInState(assistantMsg)
                        _state.value = _state.value.copy(isStreaming = false, assistantActivity = null)
                    }
                }
            }
        }
    }

    fun togglePin(conversationId: String) {
        val conv = chatRepository.getConversationById(conversationId) ?: return
        chatRepository.toggleConversationPin(conversationId, !conv.isPinned)
        loadConversations()
    }

    fun toggleArchive(conversationId: String) {
        val conv = chatRepository.getConversationById(conversationId) ?: return
        chatRepository.toggleConversationArchive(conversationId, !conv.isArchived)
        if (conv.id == _state.value.currentConversationId && !conv.isArchived) {
            _state.value = _state.value.copy(
                currentConversationId = null,
                messages = emptyList(),
                attachments = emptyList(),
                messageAttachments = emptyMap()
            )
        }
        loadConversations()
    }

    fun deleteConversation(conversationId: String) {
        chatRepository.deleteConversation(conversationId)
        if (conversationId == _state.value.currentConversationId) {
            _state.value = _state.value.copy(
                currentConversationId = null,
                messages = emptyList(),
                attachments = emptyList(),
                messageAttachments = emptyMap()
            )
        }
        loadConversations()
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        chatRepository.updateConversationTitle(conversationId, newTitle)
        loadConversations()
    }

    private fun updateMessageInState(updatedMsg: ChatItem) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == updatedMsg.id) updatedMsg else it
            }
        )
    }

    private companion object {
        const val STREAM_RENDER_INTERVAL_MILLIS = 16L
    }
}
