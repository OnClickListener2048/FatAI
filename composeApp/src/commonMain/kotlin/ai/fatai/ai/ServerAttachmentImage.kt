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
import coil3.request.ImageRequest

/**
 * Renders a server-backed attachment image from `GET /v1/files/{file_id}`.
 *
 * The persisted local path of a picked file is only valid for the session that picked it:
 * on Android it is a content URI whose temporary permission grant dies with the process, so
 * after an app restart the old local path can no longer be opened. Server assets are therefore
 * always rendered from the server; [onImageRequest] builds a Coil request with the Bearer
 * header, and Coil's memory/disk caches make scrolling through a LazyColumn free: an item that
 * was disposed and recreated hits the cache instead of re-downloading the bytes.
 */
@Composable
fun ServerAttachmentImage(
    asset: FileAsset,
    onImageRequest: suspend (FileAsset) -> ImageRequest,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    var request by remember(asset.id) { mutableStateOf<ImageRequest?>(null) }
    LaunchedEffect(asset.id) {
        // The token provider performs a network call when its cache is cold; a failure just
        // leaves the placeholder in place instead of crashing the composition.
        request = runCatching { onImageRequest(asset) }.getOrNull()
    }
    // The placeholder doubles as the failure state: failed loads stay null, keeping the
    // surface-colored box instead of an empty hole in the message.
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        placeholder = placeholder,
        fallback = placeholder,
        error = placeholder
    )
}
