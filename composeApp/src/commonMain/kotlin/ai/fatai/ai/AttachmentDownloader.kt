package ai.fatai.ai

import ai.fatai.feature.files.FileAsset
import ai.fatai.viewmodel.LOCAL_ATTACHMENT_PREFIX

/**
 * Whether the download affordance is meaningful for [asset] on this platform.
 *
 * On Android a `local-` asset is already on the device, so its button is hidden; on
 * desktop/iOS saving a copy is still useful.
 */
expect fun canDownloadAttachment(asset: FileAsset): Boolean

/** True when the asset was never uploaded and exists only under [FileAsset.localPath]. */
internal fun FileAsset.isLocalOnly(): Boolean = id.startsWith(LOCAL_ATTACHMENT_PREFIX)
