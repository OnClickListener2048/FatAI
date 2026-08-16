package ai.fatai.viewmodel

import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatUsage
import ai.fatai.chat.ProviderConfig
import ai.fatai.chat.ProviderToolCall
import ai.fatai.core.locale.currentLanguageTag
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.memory.ConversationMemoryService
import ai.fatai.feature.model.ChatContext
import ai.fatai.feature.model.ChatDocument
import ai.fatai.feature.model.FatAiServerSync
import ai.fatai.feature.model.ModelGateway
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.feature.workspace.INBOX_WORKSPACE_ID
import ai.fatai.repo.ChatItem
import ai.fatai.repo.ChatRepository
import ai.fatai.repo.MessageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Tracks the single in-flight stream so the ViewModel can stop it: [job] is the running
 * coroutine, [stopRequested] is the cooperative flag checked inside the chunk loop.
 */
class StreamControl {
    var job: Job? = null
    var stopRequested = false
}

/**
 * Immutable description of one streaming request, shared by the send and continue paths.
 *
 * The two flows differ only in which extras they attach: the send path executes docling
 * conversions, enqueues outbox fallbacks, attaches tool sources and summarizes the
 * conversation afterwards; the continue path simply streams on top of the existing history.
 */
class StreamRequest(
    val conversationId: String,
    /** The placeholder assistant message id; also sent as [ChatContext.assistantMessageId] so
     *  the server persists the streamed turn under the same id. */
    val assistantMessageId: String,
    val history: List<ChatMessage>,
    val config: ProviderConfig,
    /** Attachments read by the server (docling) before streaming; empty for the continue path. */
    val attachments: List<FileAsset> = emptyList(),
    /** False for attachment analysis so the server skips contextual references. */
    val includeContextualReferences: Boolean = true,
    val userMessageId: String? = null,
    /** When the server did not persist the turn, these messages are enqueued via the outbox. */
    val outboxMessages: List<ChatItem> = emptyList(),
    /** When the stream fails, this question is enqueued via the outbox as a fallback. */
    val fallbackQuestion: ChatItem? = null,
    /** When non-null, the conversation is summarized after the response completes. */
    val summarizeConversation: List<ChatItem>? = null,
    /** The regenerated-away assistant message to delete once the replacement succeeded. */
    val replaceMessageId: String? = null
)

/**
 * Owns the model streaming pipeline: placeholder creation, chunk collection with frame-rate
 * throttling, persistence, tool-source assembly and error handling.
 *
 * Kept deliberately state-free: the ViewModel passes its current state through [getState] and
 * receives every mutation through [onState], so the manager never writes to the flow itself.
 */
class ChatStreamManager(
    private val chatRepository: ChatRepository,
    private val modelGateway: ModelGateway,
    private val conversationMemoryService: ConversationMemoryService,
    private val conversationTitleService: ConversationTitleService,
    private val currentUser: CurrentUserProvider,
    private val serverSync: FatAiServerSync,
    private val scope: CoroutineScope,
    private val getState: () -> ChatScreenState,
    private val onState: (ChatScreenState) -> Unit,
    private val onUpdateMessage: (ChatItem) -> Unit,
    private val onLoadConversations: () -> Unit
) {

    /** Starts streaming a [request]; the coroutine is tracked in [control]. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun stream(request: StreamRequest, control: StreamControl) {
        var assistantMsg = ChatItem(
            id = request.assistantMessageId,
            userId = currentUser.currentUserId,
            conversationId = request.conversationId,
            content = "",
            type = ChatItemType.Answer,
            contentType = MessageContentType.Markdown,
            createdAt = Clock.System.now().toEpochMilliseconds(),
            isLoading = true
        )
        onState(
            getState().copy(
                messages = getState().messages + assistantMsg,
                isStreaming = true,
                assistantActivity = AssistantActivity.Thinking
            )
        )

        control.stopRequested = false
        control.job = scope.launch {
            try {
                streamResponse(request, assistantMsg, control)
            } catch (e: CancellationException) {
                // The user stopped generation; stopGeneration() keeps the partial answer.
                throw e
            } catch (e: Exception) {
                assistantMsg = assistantMsg.copy(
                    content = "Error: ${e.message}",
                    isLoading = false
                )
                onUpdateMessage(assistantMsg)
                onState(getState().copy(isStreaming = false, assistantActivity = null))
                // The server persists chat turns only when the stream completes; enqueue the
                // question so it still reaches the server when the model call failed.
                request.fallbackQuestion?.let { question ->
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

    private suspend fun streamResponse(
        request: StreamRequest,
        initialAssistantMsg: ChatItem,
        control: StreamControl
    ) {
        var assistantMsg = initialAssistantMsg
        val isAttachmentAnalysis = request.attachments.isNotEmpty()
        val documents = if (request.attachments.isEmpty()) {
            emptyList()
        } else {
            onState(getState().copy(assistantActivity = AssistantActivity.UsingTool))
            // Legacy rows from the pre-upload flow carry local- ids that never reached the
            // server; skip them so the server never sees a file reference it cannot resolve.
            request.attachments
                .filter { !it.id.startsWith(LOCAL_ATTACHMENT_PREFIX) }
                .map { attachment ->
                    ChatDocument(
                        fileId = attachment.id,
                        displayName = attachment.displayName,
                        mimeType = attachment.mimeType
                    )
                }
        }
        val prompt = if (isAttachmentAnalysis) request.history.takeLast(1) else request.history
        val context = ChatContext(
            workspaceId = getState().currentWorkspaceId,
            conversationId = request.conversationId,
            responseLanguageTag = currentLanguageTag(),
            documents = documents,
            includeContextualReferences = request.includeContextualReferences,
            userMessageId = request.userMessageId,
            assistantMessageId = request.assistantMessageId
        )
        val result = collectModelResponse(
            prompt = prompt,
            config = request.config,
            context = context,
            onContent = { content ->
                if (content.isNotEmpty()) {
                    moveToThinkingWhenUsingTool()
                    assistantMsg = assistantMsg.copy(
                        content = assistantMsg.content + content,
                        isLoading = false
                    )
                    onUpdateMessage(assistantMsg)
                }
            },
            onReasoning = { reasoningContent ->
                if (reasoningContent.isNotEmpty()) {
                    moveToThinkingWhenUsingTool()
                    assistantMsg = assistantMsg.copy(
                        reasoningContent = assistantMsg.reasoningContent + reasoningContent,
                        isLoading = false
                    )
                    onUpdateMessage(assistantMsg)
                }
            },
            onToolCalls = { calls ->
                if (calls.isNotEmpty()) {
                    onState(getState().copy(assistantActivity = calls.activity()))
                }
            },
            control = control
        )
        val toolCalls = result.toolCalls
        // Fold the turn's usage into the conversation for immediate display. The server is the
        // authoritative counter and syncs the same totals back; this is a local-only additive
        // UPDATE (see ChatRepository.addConversationUsage) so it can never double count.
        result.usage?.let { usage ->
            chatRepository.addConversationUsage(request.conversationId, usage.promptTokens, usage.completionTokens)
        }
        // When the server failed to persist the chat turn, enqueue the messages
        // via the outbox so other devices can still receive them.
        if (!result.persisted) {
            request.outboxMessages.forEach { msg ->
                serverSync.syncMessage(
                    id = msg.id,
                    conversationId = msg.conversationId,
                    role = if (msg.type == ChatItemType.Question) "user" else "assistant",
                    content = msg.content,
                    contentType = msg.contentType.name,
                    reasoningContent = msg.reasoningContent
                )
            }
        }
        if (!control.stopRequested) {
            // The server executes every tool (including the pre-stream docling reads) and
            // surfaces the result sources on its tool_call events; dedupe by url so the same
            // page attached by two searches still shows a single chip.
            val sources = toolCalls
                .flatMap { it.sources }
                .distinctBy { it.url ?: it.label }
                .map { MessageSource(label = it.label, url = it.url) }
            if (sources.isNotEmpty()) {
                assistantMsg = assistantMsg.copy(sources = sources)
                onUpdateMessage(assistantMsg)
            }
            completeAssistantResponse(request, assistantMsg)
            // The replacement succeeded; the regenerated-away answer can now go.
            request.replaceMessageId?.let { oldMessageId ->
                if (oldMessageId != assistantMsg.id) chatRepository.deleteMessage(oldMessageId)
            }
        }
    }

    private suspend fun collectModelResponse(
        prompt: List<ChatMessage>,
        config: ProviderConfig,
        context: ChatContext = ChatContext(),
        onContent: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        onToolCalls: (List<ProviderToolCall>) -> Unit = {},
        control: StreamControl
    ): StreamResult {
        var toolCalls = emptyList<ProviderToolCall>()
        var persisted = true
        var usage: ChatUsage? = null
        var lastRenderedAt = 0L
        modelGateway.stream(
            messages = prompt,
            config = config,
            context = context
        ).collect { chunk ->
            if (control.stopRequested) return@collect
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
            if (chunk.isDone) {
                toolCalls = chunk.toolCalls
                persisted = chunk.persisted
                usage = chunk.usage
            }
        }
        return StreamResult(toolCalls, persisted, usage)
    }

    private fun completeAssistantResponse(request: StreamRequest, assistantMsg: ChatItem) {
        // The server persisted this turn during the stream; only the local cache is written here.
        chatRepository.insertMessage(
            request.conversationId,
            assistantMsg.content,
            ChatItemType.Answer,
            id = assistantMsg.id,
            sync = false,
            sources = assistantMsg.sources
        )
        // The client titles new conversations on-device (LOCAL_ONLY route). When the local
        // path fails the server's own title flow still generates while the title is default.
        request.history.firstOrNull { it.role == "user" }?.let { firstUserMessage ->
            scope.launch {
                conversationTitleService.generateIfNeeded(
                    request.conversationId,
                    firstUserMessage.content,
                    request.config
                )
            }
        }
        onState(getState().copy(isStreaming = false, assistantActivity = null))
        onLoadConversations()
        request.summarizeConversation?.let { messages ->
            scope.launch {
                conversationMemoryService.summarizeIfNeeded(
                    workspaceId = chatRepository.getConversationById(request.conversationId)?.workspaceId
                        ?: INBOX_WORKSPACE_ID,
                    conversationId = request.conversationId,
                    messages = (messages + assistantMsg).map {
                        ChatMessage(
                            role = if (it.type == ChatItemType.Question) "user" else "assistant",
                            content = it.content
                        )
                    },
                    config = request.config
                )
            }
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun awaitNextStreamFrame(lastRenderedAt: Long): Long {
        if (lastRenderedAt != 0L) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - lastRenderedAt
            val remaining = RENDER_INTERVAL_MILLIS - elapsed
            if (remaining > 0) delay(remaining)
        }
        return Clock.System.now().toEpochMilliseconds()
    }

    /** The server streams docling events before the model; switch the indicator to Thinking
     *  once real content or reasoning arrives so the UsingTool label is not stuck. */
    private fun moveToThinkingWhenUsingTool() {
        if (getState().assistantActivity == AssistantActivity.UsingTool) {
            onState(getState().copy(assistantActivity = AssistantActivity.Thinking))
        }
    }

    private fun List<ProviderToolCall>.activity(): AssistantActivity = when {
        any { it.name == "weather" } -> AssistantActivity.CheckingWeather
        any { it.name == "web_search" } -> AssistantActivity.Searching
        else -> AssistantActivity.UsingTool
    }

    private data class StreamResult(
        val toolCalls: List<ProviderToolCall>,
        val persisted: Boolean,
        val usage: ChatUsage? = null
    )

    private companion object {
        const val RENDER_INTERVAL_MILLIS = 16L
    }
}
