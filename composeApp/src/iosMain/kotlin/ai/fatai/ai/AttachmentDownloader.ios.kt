@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.fatai.ai

import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetService
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController

actual fun canDownloadAttachment(asset: FileAsset): Boolean = true

actual suspend fun downloadAttachment(
    asset: FileAsset,
    fileAssetService: FileAssetService,
    onProgress: (Long, Long) -> Unit
): AttachmentDownloadResult {
    val tempPath = NSTemporaryDirectory() + "fatai-download-" + safeFileName(asset.displayName)
    val written = if (asset.isLocalOnly()) {
        // Copy the picked local file; no bytes travel through the app.
        val source = NSURL.fileURLWithPath(asset.localPath)
        NSFileManager.defaultManager.copyItemAtURL(source, toURL = NSURL.fileURLWithPath(tempPath), error = null)
    } else {
        val bytes = try {
            fileAssetService.download(asset.id, onProgress)
        } catch (e: Exception) {
            return AttachmentDownloadResult.Failed(e.message ?: "Download failed")
        }
        bytes.toNSData().writeToFile(tempPath, atomically = true)
    }
    if (!written) {
        return AttachmentDownloadResult.Failed("Cannot prepare the temporary file")
    }
    return presentExportPicker(NSURL.fileURLWithPath(tempPath))
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
}

/** "Save to Files" — the standard iOS download UX, presented from the root view controller. */
private fun presentExportPicker(url: NSURL): AttachmentDownloadResult {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        ?: return AttachmentDownloadResult.Failed("No active window to present the save dialog")
    val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url))
    root.presentViewController(picker, animated = true, completion = null)
    return AttachmentDownloadResult.Saved
}
