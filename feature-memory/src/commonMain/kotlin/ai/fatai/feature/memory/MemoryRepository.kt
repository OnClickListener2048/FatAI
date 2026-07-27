package ai.fatai.feature.memory

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.feature.user.CurrentUserProvider
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class MemoryScope { GLOBAL, WORKSPACE, CONVERSATION }
enum class MemoryKind { FACT, SUMMARY }

data class MemoryEntry(
    val id: String,
    val userId: String,
    val scope: MemoryScope,
    val workspaceId: String?,
    val conversationId: String?,
    val kind: MemoryKind,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)

class MemoryRepository(private val queries: WatsonQueries, private val currentUser: CurrentUserProvider) {
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    fun recall(workspaceId: String?, conversationId: String?, limit: Long = 20): List<MemoryEntry> =
        queries.selectMemoriesForContext(currentUser.currentUserId, workspaceId, conversationId, limit).executeAsList().map { it.toMemoryEntry() }

    @OptIn(ExperimentalUuidApi::class)
    fun save(
        content: String,
        scope: MemoryScope,
        workspaceId: String? = null,
        conversationId: String? = null,
        kind: MemoryKind = MemoryKind.FACT
    ): MemoryEntry {
        require(content.isNotBlank()) { "Memory content cannot be blank" }
        val time = now()
        val entry = MemoryEntry(Uuid.random().toString(), currentUser.currentUserId, scope, workspaceId, conversationId, kind, content.trim(), time, time)
        queries.insertMemory(
            id = entry.id,
            userId = entry.userId,
            scope = entry.scope.name,
            workspaceId = entry.workspaceId,
            conversationId = entry.conversationId,
            kind = entry.kind.name,
            content = entry.content,
            createdAt = time,
            updatedAt = time,
            isArchived = 0L
        )
        return entry
    }

    fun archive(id: String) = queries.archiveMemory(1L, now(), id, currentUser.currentUserId)

    /** Replaces a named global profile fact, such as the user's preferred name. */
    fun replaceGlobalProfileFact(prefix: String, content: String): MemoryEntry? {
        if (queries.selectActiveGlobalFactByContent(currentUser.currentUserId, content).executeAsOneOrNull() != null) {
            return null
        }
        queries.archiveGlobalFactsByPrefix(
            updatedAt = now(),
            userId = currentUser.currentUserId,
            prefix = prefix
        )
        return save(content = content, scope = MemoryScope.GLOBAL, kind = MemoryKind.FACT)
    }
}

private fun ai.fatai.database.sqldelight.MemoryEntry.toMemoryEntry() = MemoryEntry(
    id = id,
    userId = userId,
    scope = MemoryScope.valueOf(scope),
    workspaceId = workspaceId,
    conversationId = conversationId,
    kind = MemoryKind.valueOf(kind),
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt
)
