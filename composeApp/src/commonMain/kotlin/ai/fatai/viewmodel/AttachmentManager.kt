package ai.fatai.viewmodel

import ai.fatai.feature.files.FileAssetRepository
import ai.fatai.feature.files.FileAssetService
import ai.fatai.feature.model.FatAiServerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the file-attachment flow: uploads the picked file to the server (S3-like semantics)
 * with progress reporting. A failed upload rejects the attachment with an error toast so the
 * user can retry the pick; there is no local-path fallback — the server only reads files it
 * stores.
 */
class AttachmentManager(
    private val fileAssetService: FileAssetService,
    private val fileAssetRepository: FileAssetRepository,
    private val serverSync: FatAiServerSync,
    private val scope: CoroutineScope,
    private val getState: () -> ChatScreenState,
    private val onState: (ChatScreenState) -> Unit,
    private val onToast: (String) -> Unit
) {
    /**
     * Attaches a user-selected file and uploads it in the background.
     *
     * On success the FileAsset is stored under the server-assigned id, so later document reads
     * reference the file by id only. On failure the attachment is rejected with an error toast
     * and nothing is persisted — the user retries the pick themselves.
     *
     * While an upload is in flight, the send button stays disabled ([ChatScreenState.uploads]).
     *
     * @param ensureConversation creates a conversation when none is open and returns its id.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun attachFile(
        displayName: String,
        mimeType: String,
        localPath: String,
        sizeBytes: Long,
        readBytes: suspend () -> ByteArray,
        ensureConversation: () -> String?
    ) {
        val conversationId = ensureConversation() ?: return
        val uploadId = Uuid.random().toString()
        onState(
            getState().copy(
                uploads = getState().uploads + UploadProgress(uploadId, displayName, fraction = 0f)
            )
        )
        scope.launch {
            var lastShownPercent = -1
            val uploaded = try {
                fileAssetService.upload(
                    fileName = displayName,
                    mimeType = mimeType,
                    content = readBytes(),
                    workspaceId = getState().currentWorkspaceId,
                    conversationId = conversationId,
                    onProgress = { sent, total ->
                        val fraction = if (total <= 0L) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
                        val percent = (fraction * 100).toInt()
                        if (percent != lastShownPercent) {
                            lastShownPercent = percent
                            onState(
                                getState().copy(
                                    uploads = getState().uploads.map { upload ->
                                        if (upload.id == uploadId) upload.copy(fraction = fraction) else upload
                                    }
                                )
                            )
                        }
                    }
                )
            } catch (_: Exception) {
                // Upload failed: reject the attachment outright and tell the user to retry.
                // Nothing is persisted and nothing reaches the conversation.
                onToast("Attachment upload failed, please retry.")
                onState(
                    getState().copy(uploads = getState().uploads.filterNot { it.id == uploadId })
                )
                return@launch
            }
            val assetId = uploaded.id
            fileAssetRepository.attach(
                displayName = displayName,
                mimeType = mimeType,
                localPath = localPath,
                sizeBytes = sizeBytes,
                workspaceId = getState().currentWorkspaceId,
                conversationId = conversationId,
                id = assetId,
                url = uploaded.url
            )
            // Mirror the metadata so other devices can fetch the bytes through the
            // server URL; older servers omit it and the client derives it instead.
            serverSync.syncFileAsset(
                id = assetId,
                workspaceId = getState().currentWorkspaceId,
                conversationId = conversationId,
                messageId = null,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                url = uploaded.url.ifBlank { "${fileAssetService.serverBaseUrl}/v1/files/$assetId" }
            )
            onState(
                getState().copy(
                    attachments = fileAssetRepository.pendingForConversation(conversationId),
                    uploads = getState().uploads.filterNot { it.id == uploadId }
                )
            )
        }
    }

    fun removeAttachment(id: String) {
        fileAssetRepository.delete(id)
        // Legacy local-only rows never reached the server, nothing to delete there.
        if (!id.startsWith(LOCAL_ATTACHMENT_PREFIX)) serverSync.deleteFileAsset(id)
        val conversationId = getState().currentConversationId ?: return
        onState(getState().copy(attachments = fileAssetRepository.pendingForConversation(conversationId)))
    }
}
