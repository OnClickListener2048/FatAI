package ai.fatai.viewmodel

import ai.fatai.feature.files.FileAssetRepository
import ai.fatai.feature.files.FileAssetService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the file-attachment flow: uploads the picked file to the server (S3-like semantics)
 * with progress reporting and falls back to a [LOCAL_ATTACHMENT_PREFIX] id when the upload
 * fails, so the legacy local-path read path still works.
 */
class AttachmentManager(
    private val fileAssetService: FileAssetService,
    private val fileAssetRepository: FileAssetRepository,
    private val scope: CoroutineScope,
    private val getState: () -> ChatScreenState,
    private val onState: (ChatScreenState) -> Unit,
    private val onToast: (String) -> Unit
) {
    /**
     * Attaches a user-selected file and uploads it in the background.
     *
     * On success the FileAsset is stored under the server-assigned id, so later document reads
     * reference the file by id only. On failure the file is attached under a [LOCAL_ATTACHMENT_PREFIX]
     * id and the legacy local-path read path is used instead.
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
            val assetId = try {
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
                ).id
            } catch (_: Exception) {
                onToast("File upload failed, using the local path instead.")
                LOCAL_ATTACHMENT_PREFIX + Uuid.random().toString()
            }
            fileAssetRepository.attach(
                displayName = displayName,
                mimeType = mimeType,
                localPath = localPath,
                sizeBytes = sizeBytes,
                workspaceId = getState().currentWorkspaceId,
                conversationId = conversationId,
                id = assetId
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
        val conversationId = getState().currentConversationId ?: return
        onState(getState().copy(attachments = fileAssetRepository.pendingForConversation(conversationId)))
    }
}
