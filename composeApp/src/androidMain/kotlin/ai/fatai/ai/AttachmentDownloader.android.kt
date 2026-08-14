package ai.fatai.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetService
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context

actual fun canDownloadAttachment(asset: FileAsset): Boolean = !asset.isLocalOnly()

actual suspend fun downloadAttachment(
    asset: FileAsset,
    fileAssetService: FileAssetService,
    onProgress: (Long, Long) -> Unit
): AttachmentDownloadResult {
    // Local-only assets never reach the server; the UI hides their button on Android
    // (canDownloadAttachment), so this only triggers if called programmatically.
    if (asset.isLocalOnly()) {
        return AttachmentDownloadResult.Failed("Local-only attachments are not downloadable on Android")
    }
    return enqueueSystemDownload(asset, fileAssetService)
}

/**
 * Server-backed assets go through the system DownloadManager: the system process performs the
 * HTTP fetch (with the Bearer header attached), writes into the public Downloads directory and
 * reports progress in a notification, even if the app is backgrounded.
 */
private suspend fun enqueueSystemDownload(
    asset: FileAsset,
    fileAssetService: FileAssetService
): AttachmentDownloadResult {
    val context = FileKit.context
    return runCatching {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(
            Uri.parse("${fileAssetService.serverBaseUrl}/v1/files/${asset.id}")
        )
            .setTitle(asset.displayName)
            .setDescription(asset.mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName(asset.displayName))
            .addRequestHeader("Authorization", "Bearer ${fileAssetService.accessTokenProvider()}")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        manager.enqueue(request)
        AttachmentDownloadResult.Enqueued
    }.getOrElse { AttachmentDownloadResult.Failed(it.message ?: "Download failed") }
}
