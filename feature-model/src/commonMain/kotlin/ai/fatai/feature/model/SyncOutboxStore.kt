package ai.fatai.feature.model

import ai.fatai.database.sqldelight.SyncOutbox
import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.feature.user.CurrentUserProvider
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class PendingSyncOperation(
    val id: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val sequence: Long,
    val schemaVersion: Long,
    val payload: String,
    val attemptCount: Long
)

/** Durable local queue shared by every repository that mirrors data to FatAI Server. */
@OptIn(kotlin.time.ExperimentalTime::class)
class SyncOutboxStore(
    private val queries: WatsonQueries,
    private val currentUser: CurrentUserProvider
) {
    init {
        val now = Clock.System.now().toEpochMilliseconds()
        queries.recoverSyncOutbox(now, now, currentUser.currentUserId)
    }

    @OptIn(kotlin.time.ExperimentalTime::class, ExperimentalUuidApi::class)
    fun enqueue(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String,
        schemaVersion: Long = 1
    ): String {
        require(operation == "UPSERT" || operation == "DELETE") { "Unsupported sync operation: $operation" }
        val now = Clock.System.now().toEpochMilliseconds()
        val userId = currentUser.currentUserId
        val currentSequence = queries.selectSyncSequence(userId, entityType, entityId).executeAsOneOrNull()
        val sequence = (currentSequence ?: 0L) + 1L
        if (currentSequence == null) {
            queries.insertSyncSequence(userId, entityType, entityId, sequence)
        } else {
            queries.updateSyncSequence(sequence, userId, entityType, entityId)
        }
        // Keep only the newest unsent state for an entity; an in-flight task remains intact.
        queries.coalescePendingSyncOutbox(userId, entityType, entityId)
        val id = Uuid.random().toString()
        queries.insertSyncOutbox(
            id = id,
            userId = userId,
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            sequence = sequence,
            schemaVersion = schemaVersion,
            payload = payload,
            createdAt = now,
            nextAttemptAt = now,
            updatedAt = now
        )
        return id
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    fun pending(now: Long, limit: Long): List<PendingSyncOperation> =
        queries.selectPendingSyncOutbox(currentUser.currentUserId, now, limit).executeAsList().map { it.toPending() }

    @OptIn(kotlin.time.ExperimentalTime::class)
    fun markSending(id: String) {
        queries.markSyncOutboxSending(
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            id = id,
            userId = currentUser.currentUserId
        )
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    fun markRetrying(
        operation: PendingSyncOperation,
        attemptCount: Long,
        nextAttemptAt: Long,
        errorCode: String,
        errorMessage: String
    ) {
        queries.markSyncOutboxRetrying(
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            lastErrorCode = errorCode,
            lastErrorMessage = errorMessage,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            id = operation.id,
            userId = currentUser.currentUserId
        )
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    fun markFailed(
        operation: PendingSyncOperation,
        attemptCount: Long,
        errorCode: String,
        errorMessage: String
    ) {
        queries.markSyncOutboxFailed(
            attemptCount = attemptCount,
            lastErrorCode = errorCode,
            lastErrorMessage = errorMessage,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            id = operation.id,
            userId = currentUser.currentUserId
        )
    }

    fun markSucceeded(id: String) = queries.deleteSyncOutbox(id, currentUser.currentUserId)

    fun pendingCount(): Long = queries.countPendingSyncOutbox(currentUser.currentUserId).executeAsOne()

    fun cursor(): Long = queries
        .selectAppSetting(currentUser.currentUserId, SYNC_CURSOR_KEY)
        .executeAsOneOrNull()
        ?.value_
        ?.toLongOrNull()
        ?: 0L

    @OptIn(kotlin.time.ExperimentalTime::class)
    fun updateCursor(cursor: Long) {
        queries.upsertAppSetting(
            userId = currentUser.currentUserId,
            key = SYNC_CURSOR_KEY,
            value = cursor.toString(),
            updatedAt = Clock.System.now().toEpochMilliseconds()
        )
    }

    private companion object {
        const val SYNC_CURSOR_KEY = "fat_ai_server_sync_cursor"
    }
}

private fun SyncOutbox.toPending() = PendingSyncOperation(
    id = id,
    entityType = entityType,
    entityId = entityId,
    operation = operation,
    sequence = sequence,
    schemaVersion = schemaVersion,
    payload = payload,
    attemptCount = attemptCount
)
