package ai.fatai.ai

import ai.fatai.feature.files.FileAsset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * Renders a server-backed attachment image from `GET /v1/files/{file_id}`.
 *
 * The persisted local path of a picked file is only valid for the session that picked it:
 * on Android it is a content URI whose temporary permission grant dies with the process, so
 * after an app restart the old local path can no longer be opened. Server assets are therefore
 * always rendered from the server; [loadBytes] fetches the bytes through the authenticated
 * Ktor client and Coil decodes them (with downsampling and an in-memory cache).
 */
@Composable
fun ServerAttachmentImage(
    asset: FileAsset,
    loadBytes: suspend (FileAsset) -> ByteArray?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bytes by remember(asset.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(asset.id) {
        bytes = loadBytes(asset)
    }
    // The placeholder doubles as the failure state: failed loads stay null, keeping the
    // surface-colored box instead of an empty hole in the message.
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = bytes,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        placeholder = placeholder,
        fallback = placeholder,
        error = placeholder
    )
}
