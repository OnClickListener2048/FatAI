package ai.fatai.chat

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderType(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    OpenAI("OpenAI", "https://api.openai.com/v1", "gpt-4o"),
    DeepSeek("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash"),
    Gemini("Gemini", "https://generativelanguage.googleapis.com", "gemini-2.0-flash"),
    Claude("Claude", "https://api.anthropic.com", "claude-3-5-sonnet-20241022"),
    OpenRouter("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o"),
    Ollama("Ollama", "http://localhost:11434", "llama3.2"),
    Custom("Custom", "", "");

    val isOpenAICompatible: Boolean
        get() = this == OpenAI || this == DeepSeek || this == OpenRouter || this == Ollama || this == Custom

    /** Providers whose OpenAI-compatible endpoint honors the `thinking` parameter. */
    val supportsThinkingMode: Boolean
        get() = this == DeepSeek
}
