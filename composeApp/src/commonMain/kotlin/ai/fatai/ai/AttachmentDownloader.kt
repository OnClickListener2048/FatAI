package ai.fatai.ai

import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetService
import ai.fatai.viewmodel.LOCAL_ATTACHMENT_PREFIX

/** Outcome of [downloadAttachment] / [awaitAttachmentDownload], surfaced to the user. */
sealed interface AttachmentDownloadResult {
    /** The system DownloadManager took over (Android); progress lives in its notification. */
    data class Enqueued(val downloadId: Long) : AttachmentDownloadResult

    /** Bytes written to a user-chosen location; [fileName] is the final file name when known. */
    data class Saved(val fileName: String? = null) : AttachmentDownloadResult

    /** The user dismissed the save dialog without picking a location. */
    data object Cancelled : AttachmentDownloadResult

    data class Failed(val message: String) : AttachmentDownloadResult
}

/**
 * Saves [asset] onto the user's device.
 *
 * - Server-backed assets (id not prefixed `local-`) are fetched from the FatAI server through
 *   [fileAssetService]; on Android the request is handed to the system DownloadManager so the
 *   download runs outside the app and shows a system notification.
 * - Local assets (upload failed, `local-` prefix) are copied from [FileAsset.localPath]; on
 *   Android they never reach the server, so the UI hides their button ([canDownloadAttachment])
 *   and [downloadAttachment] fails fast for them.
 */
expect suspend fun downloadAttachment(
    asset: FileAsset,
    fileAssetService: FileAssetService,
    onProgress: (Long, Long) -> Unit = { _, _ -> }
): AttachmentDownloadResult

/**
 * Waits for a download enqueued with the system DownloadManager to finish and reports the
 * outcome. Polls the DownloadManager state so the app can show in-app feedback ("saved",
 * "failed") once the transfer completes; the polled coroutine dies with the process, while
 * the system download itself continues and still posts its notification.
 *
 * Only meaningful for the [Enqueued] result of [downloadAttachment] on Android; other
 * platforms never return [Enqueued] and report a failure here.
 */
expect suspend fun awaitAttachmentDownload(downloadId: Long): AttachmentDownloadResult

/**
 * Opens a file downloaded by the system DownloadManager (Android) with the platform viewer.
 * On Android 11+ the public Downloads directory is scoped storage, so the file is resolved
 * through MediaStore instead of by path. Returns false when nothing can be opened; only
 * meaningful on Android, other platforms report false.
 */
expect suspend fun openDownloadedAttachment(fileName: String): Boolean

/**
 * Whether the download affordance is meaningful for [asset] on this platform.
 *
 * On Android a `local-` asset is already on the device and would not go through the system
 * DownloadManager, so its button is hidden; on desktop/iOS saving a copy is still useful.
 */
expect fun canDownloadAttachment(asset: FileAsset): Boolean

/** True when the asset was never uploaded and exists only under [FileAsset.localPath]. */
internal fun FileAsset.isLocalOnly(): Boolean = id.startsWith(LOCAL_ATTACHMENT_PREFIX)

/** Keeps a display name usable as a file name (no path separators, non-blank). */
internal fun safeFileName(name: String): String =
    name.replace(Regex("[/\\\\]"), "_").ifBlank { "attachment" }
