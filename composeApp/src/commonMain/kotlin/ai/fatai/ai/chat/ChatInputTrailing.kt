package ai.fatai.ai.chat

import ai.fatai.feature.files.FileAsset
import ai.fatai.viewmodel.UploadProgress
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Paperclip
import compose.icons.feathericons.Send
import compose.icons.feathericons.Square
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.attach_file
import fatai.composeapp.generated.resources.send
import fatai.composeapp.generated.resources.stop
import org.jetbrains.compose.resources.stringResource

/**
 * Trailing actions of the input field: attach button, and either a stop button while
 * streaming or a circular send button when idle.
 */
@Composable
internal fun ChatInputTrailing(
    enabled: Boolean,
    isStreaming: Boolean,
    text: String,
    attachments: List<FileAsset>,
    uploads: List<UploadProgress>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onAttach, enabled = enabled && !isStreaming) {
            Icon(FeatherIcons.Paperclip, stringResource(Res.string.attach_file))
        }
        if (isStreaming) {
            IconButton(onClick = onStop) {
                Icon(
                    FeatherIcons.Square,
                    stringResource(Res.string.stop),
                    // The text field is disabled while streaming, which would
                    // otherwise tint this icon with the disabled color.
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val canSend = enabled && (text.isNotBlank() || attachments.isNotEmpty()) && uploads.isEmpty()
            Box(
                modifier = Modifier
                    .padding(start = 4.dp, end = 4.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    )
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Send,
                    stringResource(Res.string.send),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
