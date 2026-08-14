package ai.fatai.ai.chat

import ai.fatai.ai.MarkdownMessage
import ai.fatai.ai.PlatformMessageContextMenu
import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.feature.files.FileAsset
import ai.fatai.repo.ChatItem
import ai.fatai.viewmodel.AssistantActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.RefreshCw
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.copy_message
import fatai.composeapp.generated.resources.regenerate
import org.jetbrains.compose.resources.stringResource

/** One message bubble — question on the right, assistant answer on the left. */
@Composable
internal fun ChatBubble(
    msg: ChatItem,
    showThinking: Boolean,
    assistantActivity: AssistantActivity?,
    compactLayout: Boolean,
    attachments: List<FileAsset>,
    showRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDownloadAttachment: (FileAsset) -> Unit,
    onImageRequest: suspend (FileAsset) -> ImageRequest
) {
    val clipboardManager = LocalClipboardManager.current
    val isQuestion = msg.type == ChatItemType.Question
    val avatarSize = if (compactLayout) 24.dp else 28.dp
    val bubblePaddingHorizontal = if (compactLayout) 12.dp else 14.dp
    val bubblePaddingVertical = if (compactLayout) 10.dp else 12.dp
    val textSize = if (compactLayout) 14.sp else 15.sp
    val lineHeight = if (compactLayout) 20.sp else 22.sp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isQuestion) Arrangement.End else Arrangement.Start
    ) {
        if (!isQuestion) {
            Box(
                modifier = Modifier.size(avatarSize).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(if (compactLayout) 6.dp else 8.dp))
        }

        PlatformMessageContextMenu(
            enabled = !isQuestion,
            copyLabel = stringResource(Res.string.copy_message),
            onCopy = { clipboardManager.setText(AnnotatedString(msg.content)) }
        ) {
            Card(
                modifier = Modifier.widthIn(max = if (isQuestion) 520.dp else 720.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isQuestion)
                        MaterialTheme.colorScheme.surfaceContainer
                    else
                        Color.Transparent
                ),
                shape = if (isQuestion) RoundedCornerShape(18.dp)
                        else RoundedCornerShape(0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = bubblePaddingHorizontal,
                        vertical = bubblePaddingVertical
                    )
                ) {
                    when (msg.contentType) {
                        MessageContentType.Markdown -> {
                            if (msg.content.isNotBlank()) {
                                MarkdownMessage(
                                    markdown = msg.content,
                                    document = msg.markdownDocument,
                                    compactLayout = compactLayout,
                                    textColor = MaterialTheme.colorScheme.onSurface
                                )
                            } else if (msg.reasoningContent.isNotBlank()) {
                                SelectionContainer {
                                    Text(
                                        msg.reasoningContent,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = textSize,
                                            lineHeight = lineHeight
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        else -> SelectionContainer {
                            Text(
                                msg.content,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = textSize,
                                    lineHeight = lineHeight,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                    if (msg.sources.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        MessageSources(msg.sources)
                    }
                    if (attachments.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        MessageAttachments(
                            attachments,
                            onDownload = onDownloadAttachment,
                            onImageRequest = onImageRequest
                        )
                    }
                    if (!isQuestion && msg.content.isNotBlank() && !msg.isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(msg.content)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    FeatherIcons.Copy,
                                    contentDescription = stringResource(Res.string.copy_message),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (showRegenerate) {
                                IconButton(
                                    onClick = onRegenerate,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        FeatherIcons.RefreshCw,
                                        contentDescription = stringResource(Res.string.regenerate),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    // Show "Thinking" only until the first stream chunk arrives. Afterwards the
                    // changing response text is the progress indicator.
                    if (msg.isLoading || (!isQuestion && showThinking && msg.content.isBlank())) {
                        Spacer(Modifier.height(4.dp))
                        ActivityIndicator(assistantActivity)
                    }
                }
            }
        }

    }
}
