package ai.fatai.ai

import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetService
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

actual fun canDownloadAttachment(asset: FileAsset): Boolean = true

actual suspend fun downloadAttachment(
    asset: FileAsset,
    fileAssetService: FileAssetService,
    onProgress: (Long, Long) -> Unit
): AttachmentDownloadResult {
    val bytes = if (asset.isLocalOnly()) {
        val file = File(asset.localPath)
        if (!file.isFile) {
            return AttachmentDownloadResult.Failed("Local file no longer exists: ${asset.localPath}")
        }
        file.readBytes()
    } else {
        try {
            fileAssetService.download(asset.id, onProgress)
        } catch (e: Exception) {
            return AttachmentDownloadResult.Failed(e.message ?: "Download failed")
        }
    }
    return saveBytesWithDialog(asset, bytes)
}

/** Shows a native save dialog (modal FileDialog) on the AWT event-dispatch thread. */
private suspend fun saveBytesWithDialog(asset: FileAsset, bytes: ByteArray): AttachmentDownloadResult =
    withContext(Dispatchers.Swing) {
        try {
            val dialog = FileDialog(null as Frame?, "Save attachment", FileDialog.SAVE)
            dialog.file = safeFileName(asset.displayName)
            dialog.isVisible = true
            val chosen = dialog.files.firstOrNull()
                ?: return@withContext AttachmentDownloadResult.Cancelled
            chosen.writeBytes(bytes)
            AttachmentDownloadResult.Saved
        } catch (e: Exception) {
            AttachmentDownloadResult.Failed(e.message ?: "Save failed")
        }
    }
