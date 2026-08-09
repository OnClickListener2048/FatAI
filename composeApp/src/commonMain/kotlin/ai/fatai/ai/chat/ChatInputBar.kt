package ai.fatai.ai.chat

import ai.fatai.ai.desktopSendOnEnter
import ai.fatai.ai.openFileWithSystemApplication
import ai.fatai.feature.files.FileAsset
import ai.fatai.viewmodel.UploadProgress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Paperclip
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.add_api_key_in_settings
import fatai.composeapp.generated.resources.attach_file
import fatai.composeapp.generated.resources.message_fatai
import org.jetbrains.compose.resources.stringResource

/**
 * Message composer: attachment chips, upload progress chips, and the rounded input field.
 * Sending is disabled while uploading so the file upload completes first.
 */
@Composable
internal fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isStreaming: Boolean,
    onStop: () -> Unit,
    attachments: List<FileAsset>,
    uploads: List<UploadProgress>,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).align(Alignment.TopCenter)
        ) {
            if (attachments.isNotEmpty() || uploads.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    items(attachments, key = { it.id }) { asset ->
                        AttachmentChip(asset = asset, onRemove = onRemoveAttachment)
                    }
                    items(uploads, key = { it.id }) { upload ->
                        UploadProgressChip(upload)
                    }
                }
            }
            if (isStreaming) {
                Spacer(Modifier.height(4.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            stringResource(
                                if (enabled) Res.string.message_fatai else Res.string.add_api_key_in_settings
                            )
                        )
                    },
                    enabled = enabled && !isStreaming,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .desktopSendOnEnter(
                            enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty()) && !isStreaming && uploads.isEmpty(),
                            onSend = onSend
                        ),
                    trailingIcon = {
                        ChatInputTrailing(
                            enabled = enabled,
                            isStreaming = isStreaming,
                            text = text,
                            attachments = attachments,
                            uploads = uploads,
                            onAttach = onAttach,
                            onSend = onSend,
                            onStop = onStop
                        )
                    },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 4
                )
            }
        }
    }
}

/** Attached-file chip with a remove button. */
@Composable
private fun AttachmentChip(asset: FileAsset, onRemove: (String) -> Unit) {
    Card(
        modifier = Modifier.clickable { openFileWithSystemApplication(asset.localPath, asset.mimeType) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp)
        ) {
            Icon(
                FeatherIcons.Paperclip,
                contentDescription = null,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                asset.displayName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 130.dp)
            )
            TextButton(
                onClick = { onRemove(asset.id) },
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("×")
            }
        }
    }
}
