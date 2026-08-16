package ai.fatai.chat

interface ChatProvider {
    val type: ProviderType

    /** Synchronous, non-streaming completion (the on-device local model path only). */
    suspend fun chatSync(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Result<String>
}
