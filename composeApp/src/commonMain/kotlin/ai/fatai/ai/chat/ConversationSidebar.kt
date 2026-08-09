package ai.fatai.ai.chat

import ai.fatai.feature.user.User
import ai.fatai.feature.workspace.Workspace
import ai.fatai.repo.Conversation
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.cancel
import fatai.composeapp.generated.resources.create_workspace
import fatai.composeapp.generated.resources.delete
import fatai.composeapp.generated.resources.delete_conversation_confirmation
import fatai.composeapp.generated.resources.new_chat
import fatai.composeapp.generated.resources.personal
import fatai.composeapp.generated.resources.search_chats
import fatai.composeapp.generated.resources.settings
import fatai.composeapp.generated.resources.switch_workspace
import fatai.composeapp.generated.resources.user_account
import org.jetbrains.compose.resources.stringResource

/**
 * Left-hand navigation: workspace switcher, conversation search/list, and the user card.
 * Used both inside a [androidx.compose.material3.ModalNavigationDrawer] (compact layout)
 * and as a fixed-width [androidx.compose.foundation.layout.Row] sibling (wide layout).
 */
@Composable
internal fun ConversationSidebar(
    conversations: List<Conversation>,
    user: User,
    workspaces: List<Workspace>,
    currentWorkspaceId: String,
    currentId: String?,
    onSelectWorkspace: (String) -> Unit,
    onCreateWorkspace: (String, String) -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onToggleArchive: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSettings: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var showCreateWorkspace by remember { mutableStateOf(false) }
    val activeWorkspace = workspaces.find { it.id == currentWorkspaceId }

    Column(modifier = Modifier.fillMaxHeight().padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "F",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    "FatAI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    FeatherIcons.Settings,
                    contentDescription = stringResource(Res.string.settings)
                )
            }
        }

        Button(
            onClick = onNew,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp)); Text(stringResource(Res.string.new_chat))
        }

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { workspaceMenuExpanded = true },
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        FeatherIcons.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        activeWorkspace?.name ?: stringResource(Res.string.personal),
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Icon(
                        FeatherIcons.ChevronDown,
                        contentDescription = stringResource(Res.string.switch_workspace),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = workspaceMenuExpanded,
                onDismissRequest = { workspaceMenuExpanded = false }) {
                workspaces.forEach { workspace ->
                    DropdownMenuItem(
                        text = { Text(workspace.name) },
                        onClick = { onSelectWorkspace(workspace.id); workspaceMenuExpanded = false }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.create_workspace)) },
                    leadingIcon = { Icon(FeatherIcons.Plus, contentDescription = null) },
                    onClick = { workspaceMenuExpanded = false; showCreateWorkspace = true }
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; onSearch(it) },
            placeholder = { Text(stringResource(Res.string.search_chats)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )

        val sorted = conversations.sortedWith(
            compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sorted, key = { it.id }) { conv ->
                ConversationListItem(
                    conv = conv,
                    isSelected = conv.id == currentId,
                    onSelect = { onSelect(conv.id) },
                    onTogglePin = { onTogglePin(conv.id) },
                    onToggleArchive = { onToggleArchive(conv.id) },
                    onDelete = { showDeleteDialog = conv.id }
                )
            }
        }

        HorizontalDivider()
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onSettings),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.name.firstOrNull()?.uppercase() ?: "U",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.name, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        stringResource(Res.string.user_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    FeatherIcons.Settings,
                    contentDescription = stringResource(Res.string.settings),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (showDeleteDialog != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text(stringResource(Res.string.delete)) },
                text = { Text(stringResource(Res.string.delete_conversation_confirmation)) },
                confirmButton = {
                    TextButton(onClick = {
                        onDelete(showDeleteDialog!!); showDeleteDialog = null
                    }) {
                        Text(
                            stringResource(Res.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteDialog = null
                    }) { Text(stringResource(Res.string.cancel)) }
                }
            )
        }

        if (showCreateWorkspace) {
            CreateWorkspaceDialog(
                onDismiss = { showCreateWorkspace = false },
                onCreate = { name, prompt ->
                    onCreateWorkspace(name, prompt)
                    showCreateWorkspace = false
                }
            )
        }
    }
}
