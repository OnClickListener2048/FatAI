package ai.fatai.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ai.fatai.ai.isLocalOnly
import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ProviderConfig
import ai.fatai.chat.ProviderType
import ai.fatai.feature.model.FatAiServerSync
import ai.fatai.feature.model.ModelGateway
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetRepository
import ai.fatai.feature.files.FileAssetService
import ai.fatai.feature.memory.ConversationMemoryService
import ai.fatai.feature.memory.UserMemoryExtractionService
import ai.fatai.feature.tools.ToolRegistry
import ai.fatai.feature.workspace.WorkspaceRepository
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.repo.ChatItem
import ai.fatai.repo.ChatRepository
import ai.fatai.repo.ApiKeyRepository
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The bytes of an attachment that the UI should save through the platform save dialog.
 * Produced by [AIChatViewModel.downloadAttachment]; the UI opens the picker (FileKit) so
 * every platform behaves the same.
 */
class SaveAttachmentRequest(val displayName: String, val mimeType: String, val bytes: ByteArray)

/**
 * State hub for the chat screen.
 *
 * Owns the [ChatScreenState] flow and delegates the two long-running concerns to focused
 * collaborators: [ChatStreamManager] for the model streaming pipeline and [AttachmentManager]
 * for file uploads. Everything else (workspaces, conversations, API keys, scroll position)
 * stays here.
 */
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
    private val serverSync: FatAiServerSync,
    private val fileAssetService: FileAssetService
) {

    private val screenModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(ChatScreenState())
    val state: StateFlow<ChatScreenState> = _state.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    /** Fires when attachment bytes are ready and the UI should open the save dialog. */
    private val _saveAttachmentRequests = MutableSharedFlow<SaveAttachmentRequest>()
    val saveAttachmentRequests: SharedFlow<SaveAttachmentRequest> = _saveAttachmentRequests.asSharedFlow()

    /** Tracks the in-flight model stream so [stopGeneration] can cancel it. */
    private val streamControl = StreamControl()

    private val chatStreamManager = ChatStreamManager(
        chatRepository = chatRepository,
        modelGateway = modelGateway,
        toolRegistry = toolRegistry,
        conversationMemoryService = conversationMemoryService,
        currentUser = currentUser,
        serverSync = serverSync,
        scope = screenModelScope,
        getState = { _state.value },
        onState = { _state.value = it },
        onUpdateMessage = ::updateMessageInState,
        onLoadConversations = ::loadConversations
    )

    private val attachmentManager = AttachmentManager(
        fileAssetService = fileAssetService,
        fileAssetRepository = fileAssetRepository,
        scope = screenModelScope,
        getState = { _state.value },
        onState = { _state.value = it },
        onToast = { message -> screenModelScope.launch { _toastEvents.emit(message) } }
    )

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
    }

    fun loadConversations() {
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
                    providerType = key.providerType,
                    thinkingEnabled = key.thinkingEnabled
                )
            }
        )
    }

    fun setThinkingEnabled(enabled: Boolean) {
        val config = _state.value.activeConfig ?: return
        config.configurationId?.let { id -> apiKeyRepository.setThinkingEnabled(id, enabled) }
        _state.value = _state.value.copy(
            activeConfig = config.copy(thinkingEnabled = enabled)
        )
    }

    fun updateInputText(text: String) {
        _state.value = _state.value.copy(inputText = text)
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

    /**
     * Attaches a user-selected file; the upload (with progress) runs in [AttachmentManager].
     */
    fun attachFile(
        displayName: String,
        mimeType: String,
        localPath: String,
        sizeBytes: Long,
        readBytes: suspend () -> ByteArray
    ) {
        attachmentManager.attachFile(
            displayName = displayName,
            mimeType = mimeType,
            localPath = localPath,
            sizeBytes = sizeBytes,
            readBytes = readBytes,
            ensureConversation = {
                _state.value.currentConversationId ?: run {
                    newConversation()
                    _state.value.currentConversationId
                }
            }
        )
    }

    fun removeAttachment(id: String) {
        attachmentManager.removeAttachment(id)
    }

    /**
     * Saves a message attachment onto the device: local-only assets are copied from the
     * picker path, server assets are fetched from the FatAI server. The bytes are handed to
     * the UI, which opens the platform save dialog — the same flow on all three platforms.
     */
    fun downloadAttachment(asset: FileAsset) {
        screenModelScope.launch {
            try {
                _toastEvents.emit("Downloading ${asset.displayName}…")
                val bytes = if (asset.isLocalOnly()) {
                    PlatformFile(asset.localPath).readBytes()
                } else {
                    fileAssetService.download(asset.id)
                }
                _saveAttachmentRequests.emit(
                    SaveAttachmentRequest(asset.displayName, asset.mimeType, bytes)
                )
            } catch (e: Exception) {
                _toastEvents.emit("Download failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun sendMessage(analyzeAttachedFilePrompt: String) {
        val text = _state.value.inputText.trim()
        val pendingAttachments = _state.value.attachments
        // Sending is blocked while an attachment is still uploading; the message would otherwise
        // go out without the file (its server id is not known yet).
        if ((text.isBlank() && pendingAttachments.isEmpty()) || _state.value.isStreaming || _state.value.uploads.isNotEmpty()) return

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

    fun stopGeneration() {
        streamControl.stopRequested = true
        streamControl.job?.cancel()
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

        // Remove the old answer from the UI immediately. The persisted copy is kept until
        // the replacement succeeds, so a failed stream never loses the answer for good.
        val filteredMsgs = msgs.filter { it.id != lastAssistant?.id }
        _state.value = _state.value.copy(
            messages = filteredMsgs,
            isLoading = false
        )
        streamChat(
            convId,
            filteredMsgs,
            _state.value.messageAttachments[lastQuestion.id].orEmpty(),
            replaceMessageId = lastAssistant?.id
        )
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

    @OptIn(ExperimentalUuidApi::class)
    private fun streamChat(
        conversationId: String,
        messages: List<ChatItem>,
        attachments: List<FileAsset> = emptyList(),
        replaceMessageId: String? = null
    ) {
        val config = _state.value.activeConfig ?: return
        // Blank turns (stopped before any text, tool-only rounds) carry no information to the
        // server; the server rejects empty content, so drop them instead of sending "".
        val history = messages
            .filter { !it.isLoading && it.content.isNotBlank() && !it.content.startsWith("Error:") }
            .map {
                ChatMessage(role = if (it.type == ChatItemType.Question) "user" else "assistant", content = it.content)
            }
        val isAttachmentAnalysis = attachments.isNotEmpty()
        chatStreamManager.stream(
            request = StreamRequest(
                conversationId = conversationId,
                assistantMessageId = Uuid.random().toString(),
                history = if (isAttachmentAnalysis) history.takeLast(1) else history,
                config = config,
                includeTools = true,
                attachments = attachments,
                includeContextualReferences = !isAttachmentAnalysis,
                userMessageId = messages.lastOrNull { it.type == ChatItemType.Question }?.id,
                outboxMessages = messages,
                fallbackQuestion = messages.lastOrNull { it.type == ChatItemType.Question },
                summarizeConversation = messages,
                replaceMessageId = replaceMessageId
            ),
            control = streamControl
        )
    }

    private fun updateMessageInState(updatedMsg: ChatItem) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == updatedMsg.id) updatedMsg else it
            }
        )
    }
}
