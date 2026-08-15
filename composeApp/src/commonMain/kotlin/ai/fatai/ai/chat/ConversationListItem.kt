package ai.fatai.ai.chat

import ai.fatai.repo.Conversation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.MoreVertical
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.archive
import fatai.composeapp.generated.resources.conversation_actions
import fatai.composeapp.generated.resources.delete
import fatai.composeapp.generated.resources.pin
import fatai.composeapp.generated.resources.unpin
import org.jetbrains.compose.resources.stringResource

/** Formats a cumulative token count compactly: 0 → "", 999 → "999", 1234 → "1.2k", 12345 → "12k". */
private fun formatTokenCount(total: Long): String = when {
    total <= 0 -> ""
    total < 1_000 -> "$total"
    total < 10_000 -> {
        val tenths = (total + 50) / 100
        "${tenths / 10}.${tenths % 10}k"
    }
    else -> "${(total + 500) / 1_000}k"
}

/**
 * One conversation row in the sidebar with its pin/archive/delete context menu.
 *
 * [onDelete] only opens the confirmation dialog; the actual deletion is confirmed in the
 * sidebar's [androidx.compose.material3.AlertDialog].
 */
@Composable
internal fun ConversationListItem(
    conv: Conversation,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                Color.Transparent
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (conv.isPinned) "📌 ${conv.title}" else conv.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    listOf(
                        "${conv.providerType.displayName}  ${conv.model}",
                        formatTokenCount(conv.totalPromptTokens + conv.totalCompletionTokens)
                    ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        FeatherIcons.MoreVertical,
                        contentDescription = stringResource(Res.string.conversation_actions)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (conv.isPinned) Res.string.unpin else Res.string.pin
                                )
                            )
                        },
                        onClick = { onTogglePin(); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.archive)) },
                        onClick = { onToggleArchive(); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(Res.string.delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}
