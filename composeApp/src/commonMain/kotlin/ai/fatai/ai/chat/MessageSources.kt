package ai.fatai.ai.chat

import ai.fatai.ai.openUrl
import ai.fatai.repo.MessageSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Globe

/** Collapsed row of up to three source chips; tapping opens the full list in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageSources(sources: List<MessageSource>) {
    var showAll by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { showAll = true }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        sources.take(3).forEach { source ->
            SourceFavicon(source)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "来源",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showAll) {
        ModalBottomSheet(onDismissRequest = { showAll = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                Text("信息来源", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                sources.forEach { source ->
                    SourceRow(
                        source = source,
                        onOpen = {
                            showAll = false
                            source.url?.let(::openUrl)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: MessageSource, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = source.url != null, onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        SourceFavicon(source, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                source.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (source.url != null) {
                Text(
                    source.url.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SourceFavicon(source: MessageSource, size: Dp = 18.dp) {
    val host = remember(source.url) { source.url?.let(::faviconHostOf) }
    if (host != null) {
        coil3.compose.AsyncImage(
            model = "https://www.google.com/s2/favicons?domain=$host&sz=32",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
        )
    } else {
        Icon(
            FeatherIcons.Globe,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun faviconHostOf(url: String): String? {
    val afterScheme = url.substringAfter("://", url)
    val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val hostAndPort = if (end >= 0) afterScheme.substring(0, end) else afterScheme
    return hostAndPort.substringBefore(':').ifBlank { null }
}
