package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatStreamChunk
import ai.fatai.chat.LocalRouteMode
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.tools.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Dispatches model calls between the on-device local engine and the cloud gateway.
 *
 * The route is per-task via [ProviderConfig.localRoute]: chat turns stay NONE (always cloud),
 * memory extraction uses LOCAL_FIRST (local, cloud on failure — memory quality must not drop),
 * and title generation uses LOCAL_ONLY (never cloud: the server's own title flow is the
 * fallback, so a cloud attempt here would double-bill the same title).
 */
class LocalFirstRouterGateway(
    private val serverGateway: FatAiServerModelGateway,
    private val localEngine: LocalModelEngine
) : ModelGateway {

    override suspend fun stream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition>,
        context: ChatContext
    ): Flow<ChatStreamChunk> {
        if (config.localRoute != LocalRouteMode.NONE && localEngine.isAvailable) {
            val localResult = localEngine.complete(messages, config)
            if (localResult.isSuccess) {
                val text = localResult.getOrNull().orEmpty()
                println("LOCAL => ${config.localRoute} ok, ${text.length} chars")
                return flow {
                    if (text.isNotEmpty()) emit(ChatStreamChunk(content = text))
                    emit(ChatStreamChunk(content = "", isDone = true))
                }
            }
            if (config.localRoute == LocalRouteMode.LOCAL_ONLY) {
                // The caller owns the fallback; emitting an empty done chunk keeps the caller's
                // collect loop well-formed without spending any cloud tokens.
                println("LOCAL => ${config.localRoute} failed: ${localResult.exceptionOrNull()?.message} — skipped")
                return flow { emit(ChatStreamChunk(content = "", isDone = true)) }
            }
            println("LOCAL => ${config.localRoute} failed: ${localResult.exceptionOrNull()?.message} — cloud fallback")
        }
        return serverGateway.stream(messages, config, tools, context)
    }
}
