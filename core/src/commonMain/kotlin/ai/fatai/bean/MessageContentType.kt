package ai.fatai.bean

/**
 * Describes the primary body carried by a chat message independently from its role.
 *
 * New modalities must add a renderer and payload/attachment adapter without changing
 * conversation, streaming, or provider history flows.
 */
enum class MessageContentType {
    Text,
    Markdown,
    Image,
    File,
    ToolResult,
    Thinking
}
