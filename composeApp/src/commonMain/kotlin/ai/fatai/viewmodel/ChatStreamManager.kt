package ai.fatai.viewmodel

import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ProviderConfig
import ai.fatai.core.locale.currentLanguageTag
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.memory.ConversationMemoryService
import ai.fatai.feature.model.ChatContext
import ai.fatai.feature.model.FatAiServerSync
import ai.fatai.feature.model.ModelGateway
import ai.fatai.feature.tools.ProviderToolCall
import ai.fatai.feature.tools.ToolCall
import ai.fatai.feature.tools.ToolExecution
import ai.fatai.feature.tools.ToolRegistry
import ai.fatai.feature.tools.ToolResult
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
    val includeTools: Boolean,
    /** Attachments converted via docling before streaming; empty for the continue path. */
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
    private val toolRegistry: ToolRegistry,
    private val conversationMemoryService: ConversationMemoryService,
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
        val documentExecutions = if (request.attachments.isEmpty()) {
            emptyList()
        } else {
            onState(getState().copy(assistantActivity = AssistantActivity.UsingTool))
            request.attachments.map { attachment ->
                val arguments = if (attachment.id.startsWith(LOCAL_ATTACHMENT_PREFIX)) {
                    mapOf(
                        "local_path" to attachment.localPath,
                        "display_name" to attachment.displayName,
                        "mime_type" to attachment.mimeType
                    )
                } else {
                    mapOf(
                        "file_id" to attachment.id,
                        "display_name" to attachment.displayName,
                        "mime_type" to attachment.mimeType
                    )
                }
                toolRegistry.execute(ToolCall("docling_document_read", arguments))
            }.also {
                onState(getState().copy(assistantActivity = AssistantActivity.Thinking))
            }
        }
        val toolResults = documentExecutions.map { execution -> formatToolResult(execution) }
        val prompt = if (isAttachmentAnalysis) request.history.takeLast(1) else request.history
        val context = ChatContext(
            workspaceId = getState().currentWorkspaceId,
            conversationId = request.conversationId,
            responseLanguageTag = currentLanguageTag(),
            toolResults = toolResults,
            includeContextualReferences = request.includeContextualReferences,
            userMessageId = request.userMessageId,
            assistantMessageId = request.assistantMessageId
        )
        val result = collectModelResponse(
            prompt = prompt,
            config = request.config,
            context = context,
            includeTools = request.includeTools,
            onContent = { content ->
                if (content.isNotEmpty()) {
                    assistantMsg = assistantMsg.copy(
                        content = assistantMsg.content + content,
                        isLoading = false
                    )
                    onUpdateMessage(assistantMsg)
                }
            },
            onReasoning = { reasoningContent ->
                if (reasoningContent.isNotEmpty()) {
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
            val serverToolExecutions = toolCalls.map { call ->
                ToolExecution(
                    call = ToolCall(call.name, call.arguments),
                    // The server executes the tool and returns structured sources.
                    result = ToolResult.Success("", sources = call.sources)
                )
            }
            val referencedExecutions = documentExecutions + serverToolExecutions
            if (referencedExecutions.isNotEmpty()) {
                assistantMsg = assistantMsg.withToolSources(referencedExecutions)
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
        includeTools: Boolean,
        onContent: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        onToolCalls: (List<ProviderToolCall>) -> Unit = {},
        control: StreamControl
    ): StreamResult {
        var toolCalls = emptyList<ProviderToolCall>()
        var persisted = true
        var lastRenderedAt = 0L
        modelGateway.stream(
            messages = prompt,
            config = config,
            tools = if (includeTools) toolRegistry.definitions() else emptyList(),
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
            }
        }
        return StreamResult(toolCalls, persisted)
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
        // The server generates a model-based title for new conversations and syncs it
        // back through the change stream; nothing to update locally here.
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

    private fun formatToolResult(execution: ToolExecution): String = buildString {
        appendLine("Tool: ${execution.call.toolName}")
        when (val result = execution.result) {
            is ToolResult.Success -> appendLine(result.content)
            is ToolResult.Failure -> appendLine("Tool failed (${result.code}): ${result.message}")
        }
    }.trimEnd()

    private fun List<ProviderToolCall>.activity(): AssistantActivity = when {
        any { it.name == "weather" } -> AssistantActivity.CheckingWeather
        any { it.name == "web_search" } -> AssistantActivity.Searching
        else -> AssistantActivity.UsingTool
    }

    private fun ChatItem.withToolSources(executions: List<ToolExecution>): ChatItem {
        val sources = executions
            .flatMap { execution -> (execution.result as? ToolResult.Success)?.sources.orEmpty() }
            .distinctBy { source -> source.url ?: source.label }
            .map { source -> MessageSource(label = source.label, url = source.url) }
        if (sources.isEmpty()) return this
        return copy(sources = sources)
    }

    private data class StreamResult(
        val toolCalls: List<ProviderToolCall>,
        val persisted: Boolean
    )

    private companion object {
        const val RENDER_INTERVAL_MILLIS = 16L
    }
}
