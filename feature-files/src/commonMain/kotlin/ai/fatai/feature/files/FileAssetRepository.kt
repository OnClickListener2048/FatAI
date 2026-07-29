package ai.fatai.feature.files

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.feature.user.CurrentUserProvider
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class FileAsset(
    val id: String,
    val userId: String,
    val workspaceId: String?,
    val conversationId: String?,
    val messageId: String?,
    val displayName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val createdAt: Long
)

class FileAssetRepository(private val queries: WatsonQueries, private val currentUser: CurrentUserProvider) {
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    fun forConversation(conversationId: String): List<FileAsset> =
        queries.selectFilesForConversation(conversationId, currentUser.currentUserId).executeAsList().map { it.toFileAsset() }

    fun pendingForConversation(conversationId: String): List<FileAsset> =
        queries.selectPendingFilesForConversation(conversationId, currentUser.currentUserId).executeAsList().map { it.toFileAsset() }

    @OptIn(ExperimentalUuidApi::class)
    fun attach(
        displayName: String,
        mimeType: String,
        localPath: String,
        sizeBytes: Long,
        workspaceId: String?,
        conversationId: String?,
        messageId: String? = null
    ): FileAsset {
        val asset = FileAsset(
            Uuid.random().toString(), currentUser.currentUserId, workspaceId, conversationId, messageId,
            displayName, mimeType, localPath, sizeBytes, now()
        )
        queries.insertFileAsset(
            asset.id, asset.userId, asset.workspaceId, asset.conversationId, asset.messageId,
            asset.displayName, asset.mimeType, asset.localPath, asset.sizeBytes, asset.createdAt
        )
        return asset
    }

    fun assignPendingToMessage(conversationId: String, messageId: String) {
        queries.assignPendingFilesToMessage(messageId = messageId, conversationId = conversationId, userId = currentUser.currentUserId)
    }

    fun delete(id: String) = queries.deleteFileAsset(id, currentUser.currentUserId)
}

private fun ai.fatai.database.sqldelight.FileAsset.toFileAsset() = FileAsset(
    id = id,
    userId = userId,
    workspaceId = workspaceId,
    conversationId = conversationId,
    messageId = messageId,
    displayName = displayName,
    mimeType = mimeType,
    localPath = localPath,
    sizeBytes = sizeBytes,
    createdAt = createdAt
)
