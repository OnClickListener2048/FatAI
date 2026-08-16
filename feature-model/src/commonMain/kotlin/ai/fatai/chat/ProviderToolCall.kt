package ai.fatai.chat

import kotlinx.serialization.Serializable

/** A user-visible provenance record for information returned by a tool. */
@Serializable
data class ToolSource(
    val label: String,
    val url: String? = null
)

/** A tool invocation surfaced by the server for progress display and provenance. */
@Serializable
data class ProviderToolCall(
    val id: String? = null,
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    /** Structured result sources (e.g. search pages) executed by the server. */
    val sources: List<ToolSource> = emptyList()
)
