package ai.fatai.ai

import ai.fatai.feature.files.FileAsset

actual fun canDownloadAttachment(asset: FileAsset): Boolean = !asset.isLocalOnly()
