package ai.fatai.feature.memory

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.sync.SyncMutationSink
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

class MemoryRepository(
    private val queries: WatsonQueries,
    private val currentUser: CurrentUserProvider,
    private val serverSync: SyncMutationSink? = null
) {
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
        serverSync?.syncMemory(
            id = entry.id,
            scope = entry.scope.name,
            content = entry.content,
            workspaceId = entry.workspaceId,
            conversationId = entry.conversationId,
            kind = entry.kind.name,
            isArchived = false
        )
        return entry
    }

    fun archive(id: String) {
        val entry = queries.selectMemoryById(id, currentUser.currentUserId).executeAsOneOrNull()
        queries.archiveMemory(1L, now(), id, currentUser.currentUserId)
        entry?.let {
            serverSync?.syncMemory(
                id = it.id,
                scope = it.scope,
                content = it.content,
                workspaceId = it.workspaceId,
                conversationId = it.conversationId,
                kind = it.kind,
                isArchived = true
            )
        }
    }

    fun update(id: String, content: String) {
        require(content.isNotBlank()) { "Memory content cannot be blank" }
        val entry = queries.selectMemoryById(id, currentUser.currentUserId).executeAsOneOrNull() ?: return
        val trimmed = content.trim()
        queries.updateMemory(updatedAt = now(), content = trimmed, id = id, userId = currentUser.currentUserId)
        serverSync?.syncMemory(
            id = entry.id,
            scope = entry.scope,
            content = trimmed,
            workspaceId = entry.workspaceId,
            conversationId = entry.conversationId,
            kind = entry.kind,
            isArchived = false
        )
    }

    fun clearAll() {
        val entries = queries.selectAllMemories(currentUser.currentUserId).executeAsList()
        entries.forEach { archive(it.id) }
    }

    fun getAll(scope: MemoryScope? = null): List<MemoryEntry> {
        val all = queries.selectAllMemories(currentUser.currentUserId).executeAsList().map { it.toMemoryEntry() }
        return if (scope != null) all.filter { it.scope == scope } else all
    }

    /** Upserts one model-classified global fact while retaining the latest value for its key. */
    fun upsertGlobalFact(key: String, fact: String): MemoryEntry? {
        val prefix = "$key: "
        val content = "$prefix${fact.trim()}"
        if (queries.selectActiveGlobalFactByContent(currentUser.currentUserId, content).executeAsOneOrNull() != null) {
            return null
        }
        val archived = queries.selectGlobalFactsByPrefix(currentUser.currentUserId, prefix).executeAsList()
        queries.archiveGlobalFactsByPrefix(
            updatedAt = now(),
            userId = currentUser.currentUserId,
            prefix = prefix
        )
        archived.forEach {
            serverSync?.syncMemory(
                id = it.id,
                scope = it.scope,
                content = it.content,
                workspaceId = it.workspaceId,
                conversationId = it.conversationId,
                kind = it.kind,
                isArchived = true
            )
        }
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
