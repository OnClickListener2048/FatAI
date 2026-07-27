package ai.fatai.feature.memory

import ai.fatai.chat.ChatMessage

private const val PREFERRED_NAME_PREFIX = "User preferred name: "

/**
 * Persists explicit self-identification as global, cross-conversation user context.
 *
 * This is intentionally conservative: it only records a name when the user uses a clear
 * self-identification form, instead of treating every message as a durable personal fact.
 */
class UserProfileMemoryService(private val memoryRepository: MemoryRepository) {
    fun rememberFromUserMessage(content: String) {
        extractPreferredName(content)?.let(::rememberPreferredName)
    }

    /** Lets existing conversations seed the profile when the user reopens them. */
    fun rememberFromConversation(messages: List<ChatMessage>) {
        messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role == "user") extractPreferredName(message.content) else null
            }
            ?.let(::rememberPreferredName)
    }

    private fun rememberPreferredName(name: String) {
        memoryRepository.replaceGlobalProfileFact(
            prefix = PREFERRED_NAME_PREFIX,
            content = "$PREFERRED_NAME_PREFIX$name. Address the user by this name unless they ask otherwise."
        )
    }

    private fun extractPreferredName(content: String): String? =
        chineseNameExpression.find(content)?.groupValues?.getOrNull(1)?.trim()
            ?: englishNameExpression.find(content)?.groupValues?.getOrNull(1)?.trim()

    private companion object {
        /** Covers forms such as “我叫 Watson”、“我的名字是 Watson” and “我是 watson”. */
        val chineseNameExpression = Regex(
            """(?:^|[。！？!?]\s*)(?:我叫|我的名字是|我是)\s*([A-Za-z][A-Za-z0-9 _-]{0,39})(?=\s*(?:[，。！？!?]|$))"""
        )

        /** Covers English forms such as “My name is Watson” and “Call me Watson”. */
        val englishNameExpression = Regex(
            """(?i)\b(?:my name is|call me)\s+([a-z][a-z0-9 _-]{0,39})(?=\s*(?:[,.!?]|$))"""
        )
    }
}
