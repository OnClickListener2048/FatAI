package ai.fatai.ai.chat

import ai.fatai.ai.ServerAttachmentImage
import ai.fatai.ai.canDownloadAttachment
import ai.fatai.ai.isLocalOnly
import ai.fatai.ai.openFileWithSystemApplication
import ai.fatai.feature.files.FileAsset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.Paperclip
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.download_attachment
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.coil.AsyncImage as FileKitAsyncImage
import org.jetbrains.compose.resources.stringResource

/** Image previews or file cards for the attachments of one message. */
@Composable
internal fun MessageAttachments(
    attachments: List<FileAsset>,
    onDownload: (FileAsset) -> Unit,
    onImageRequest: suspend (FileAsset) -> ImageRequest
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { asset ->
            if (asset.mimeType.startsWith("image/", ignoreCase = true)) {
                Box {
                    val imageModifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                    if (asset.isLocalOnly()) {
                        // Never reached the server; the picked path is the only copy.
                        FileKitAsyncImage(
                            file = PlatformFile(asset.localPath),
                            contentDescription = asset.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = imageModifier
                                .clickable { openFileWithSystemApplication(asset.localPath, asset.mimeType) }
                        )
                    } else {
                        // Uploaded to the server: render from the server, since the local
                        // picker URI loses its permission grant after an app restart.
                        ServerAttachmentImage(
                            asset = asset,
                            onImageRequest = onImageRequest,
                            contentDescription = asset.displayName,
                            modifier = imageModifier
                        )
                    }
                    if (canDownloadAttachment(asset)) {
                        DownloadAttachmentButton(
                            onClick = { onDownload(asset) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.clickable { openFileWithSystemApplication(asset.localPath, asset.mimeType) },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            FeatherIcons.Paperclip,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                asset.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                            Text(
                                asset.mimeType,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (canDownloadAttachment(asset)) {
                            IconButton(onClick = { onDownload(asset) }) {
                                Icon(
                                    FeatherIcons.Download,
                                    contentDescription = stringResource(Res.string.download_attachment),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Small circular download affordance shown on top of image previews. */
@Composable
private fun DownloadAttachmentButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            FeatherIcons.Download,
            contentDescription = stringResource(Res.string.download_attachment),
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(15.dp)
        )
    }
}
