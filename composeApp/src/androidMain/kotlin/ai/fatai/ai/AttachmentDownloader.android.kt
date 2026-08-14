package ai.fatai.ai

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetService
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
        AttachmentDownloadResult.Enqueued(manager.enqueue(request))
    }.getOrElse { AttachmentDownloadResult.Failed(it.message ?: "Download failed") }
}

/**
 * Polls the system DownloadManager until the enqueued transfer finishes. The system download
 * keeps running (and keeps its notification) if this app-side poll dies with the process.
 */
actual suspend fun awaitAttachmentDownload(downloadId: Long): AttachmentDownloadResult =
    withContext(Dispatchers.IO) {
        pollDownloadStatus(downloadId)
    }

private suspend fun pollDownloadStatus(downloadId: Long): AttachmentDownloadResult {
    val manager = FileKit.context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val query = DownloadManager.Query().setFilterById(downloadId)
    while (true) {
        val cursor = runCatching { manager.query(query) }.getOrNull()
            ?: return AttachmentDownloadResult.Failed("Download status is not available")
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            // The row is gone: the user removed it from the system Downloads queue.
            return AttachmentDownloadResult.Failed("Download was removed")
        }
        try {
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val localUri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    ).orEmpty()
                    val fileName = localUri.substringAfterLast('/', missingDelimiterValue = localUri)
                    return AttachmentDownloadResult.Saved(fileName.ifBlank { null })
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    return AttachmentDownloadResult.Failed(downloadFailureMessage(reason))
                }
                // STATUS_PENDING / STATUS_RUNNING / STATUS_PAUSED — keep polling.
                else -> Unit
            }
        } finally {
            cursor.close()
        }
        delay(1_000)
    }
}

private fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "A file with the same name already exists"
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network error while downloading"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "The server returned an unexpected response"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "The server redirected too many times"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage device not found"
    DownloadManager.ERROR_CANNOT_RESUME -> "The download cannot be resumed"
    DownloadManager.ERROR_FILE_ERROR -> "Error writing the file"
    else -> "Download failed"
}

/**
 * Opens a file that landed in the public Downloads directory. Android 11+ uses scoped storage,
 * so the file is resolved through MediaStore (by display name) instead of by path; on older
 * versions the direct path is shared through the FileProvider (root-path covers the whole
 * filesystem). Falls back to [openFileWithSystemApplication], which needs a mime type.
 */
actual suspend fun openDownloadedAttachment(fileName: String): Boolean =
    withContext(Dispatchers.IO) {
        val context = FileKit.context
        val resolved: Pair<String, String>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE)
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val mime = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    ).orEmpty().ifBlank { "application/octet-stream" }
                    ContentUris.withAppendedId(collection, id).toString() to mime
                } else {
                    null
                }
            }
        } else {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (file.isFile) {
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
                // Raw path: SystemFileOpener routes it through the FileProvider.
                file.absolutePath to mime
            } else {
                null
            }
        }
        resolved?.let { (uriOrPath, mime) -> openFileWithSystemApplication(uriOrPath, mime) } ?: false
    }
