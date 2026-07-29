package ai.fatai.feature.memory

import kotlinx.coroutines.flow.collect
import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.model.ModelGateway

data class MemoryPolicy(
    val recentHistoryLimit: Int = 20,
    val summaryEveryMessages: Int = 500,
    val summaryMaxTokens: Int = 600
)

/** Creates durable conversation summaries without mixing them into the transient chat state. */
class ConversationMemoryService(
    private val memoryRepository: MemoryRepository,
    private val modelGateway: ModelGateway,
    private val policy: MemoryPolicy = MemoryPolicy()
) {
    suspend fun summarizeIfNeeded(
        workspaceId: String,
        conversationId: String,
        messages: List<ChatMessage>,
        config: ProviderConfig
    ) {
        if (messages.size < policy.summaryEveryMessages || messages.size % policy.summaryEveryMessages != 0) return

        val transcript = messages.takeLast(policy.summaryEveryMessages).joinToString("\n") { message ->
            "${message.role.uppercase()}: ${message.content}"
        }
        val summaryPrompt = listOf(
            ChatMessage(
                role = "system",
                content = "Summarize this conversation for long-term memory. Preserve decisions, preferences, facts, open tasks, and constraints. Do not add facts."
            ),
            ChatMessage(role = "user", content = transcript)
        )
        val summary = StringBuilder()
        var completed = false
        modelGateway.stream(summaryPrompt, config.copy(maxTokens = policy.summaryMaxTokens, temperature = 0.2f)).collect { chunk ->
            if (completed) return@collect
            if (chunk.isDone) completed = true else summary.append(chunk.content)
        }
        if (summary.isNotBlank()) {
            memoryRepository.save(
                content = summary.toString(),
                scope = MemoryScope.CONVERSATION,
                workspaceId = workspaceId,
                conversationId = conversationId,
                kind = MemoryKind.SUMMARY
            )
        }
    }
}
