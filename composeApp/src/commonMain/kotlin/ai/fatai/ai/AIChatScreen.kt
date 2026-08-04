package ai.fatai.ai

import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.user.User
import ai.fatai.feature.user.UserRepository
import ai.fatai.feature.workspace.Workspace
import ai.fatai.repo.ApiKeyRepository
import ai.fatai.repo.Conversation
import ai.fatai.repo.MessageSource
import ai.fatai.viewmodel.AIChatViewModel
import ai.fatai.viewmodel.AssistantActivity
import ai.fatai.viewmodel.ChatScrollPosition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Menu
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Paperclip
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Send
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Square
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.add_api_key
import fatai.composeapp.generated.resources.add_api_key_description
import fatai.composeapp.generated.resources.add_api_key_in_settings
import fatai.composeapp.generated.resources.add_key
import fatai.composeapp.generated.resources.analyze_attached_file
import fatai.composeapp.generated.resources.archive
import fatai.composeapp.generated.resources.attach_file
import fatai.composeapp.generated.resources.back_to_bottom
import fatai.composeapp.generated.resources.cancel
import fatai.composeapp.generated.resources.checking_weather
import fatai.composeapp.generated.resources.continue_generation
import fatai.composeapp.generated.resources.conversation_actions
import fatai.composeapp.generated.resources.copy_message
import fatai.composeapp.generated.resources.create
import fatai.composeapp.generated.resources.create_workspace
import fatai.composeapp.generated.resources.delete
import fatai.composeapp.generated.resources.delete_conversation_confirmation
import fatai.composeapp.generated.resources.message_fatai
import fatai.composeapp.generated.resources.new_chat
import fatai.composeapp.generated.resources.new_conversation
import fatai.composeapp.generated.resources.new_workspace
import fatai.composeapp.generated.resources.open_conversations
import fatai.composeapp.generated.resources.personal
import fatai.composeapp.generated.resources.pin
import fatai.composeapp.generated.resources.regenerate
import fatai.composeapp.generated.resources.search_chats
import fatai.composeapp.generated.resources.searching
import fatai.composeapp.generated.resources.send
import fatai.composeapp.generated.resources.settings
import fatai.composeapp.generated.resources.stop
import fatai.composeapp.generated.resources.switch_workspace
import fatai.composeapp.generated.resources.thinking
import fatai.composeapp.generated.resources.unpin
import fatai.composeapp.generated.resources.user_account
import fatai.composeapp.generated.resources.using_tool
import fatai.composeapp.generated.resources.welcome_provider
import fatai.composeapp.generated.resources.welcome_start_body
import fatai.composeapp.generated.resources.welcome_start_title
import fatai.composeapp.generated.resources.welcome_title
import fatai.composeapp.generated.resources.workspace_instruction
import fatai.composeapp.generated.resources.workspace_name
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import io.github.vinceglb.filekit.coil.AsyncImage as FileKitAsyncImage

class AIChatScreen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(onSettings: () -> Unit) {
        val viewModel = koinInject<AIChatViewModel>()
        val apiKeyRepository = koinInject<ApiKeyRepository>()
        val userRepository = koinInject<UserRepository>()
        val user = remember { userRepository.currentUser() }
        var state by remember { mutableStateOf(viewModel.state.value) }
        var showApiKeyGuide by remember { mutableStateOf(apiKeyRepository.getAllKeys().isEmpty()) }

        LaunchedEffect(viewModel) {
            viewModel.refresh()
            viewModel.state.collect { state = it }
        }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val attachFileTitle = stringResource(Res.string.attach_file)
        val analyzeAttachedFilePrompt = stringResource(Res.string.analyze_attached_file)
        val filePicker = rememberFilePickerLauncher(
            type = FileKitType.File(
                listOf(
                    "pdf",
                    "doc",
                    "docx",
                    "xls",
                    "xlsx",
                    "md",
                    "txt",
                    "png",
                    "jpg",
                    "jpeg",
                    "webp"
                )
            ),
            title = attachFileTitle
        ) { file ->
            if (file != null) {
                viewModel.attachFile(
                    displayName = file.name,
                    mimeType = file.mimeType()?.toString() ?: "application/octet-stream",
                    localPath = file.toString(),
                    sizeBytes = file.size()
                )
            }
        }

        LaunchedEffect(Unit) {
            viewModel.toastEvents.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }

        @Composable
        fun Sidebar(closeAfterAction: Boolean) {
            ConversationSidebar(
                conversations = state.conversations.filter { !it.isArchived },
                user = user,
                workspaces = state.workspaces,
                currentWorkspaceId = state.currentWorkspaceId,
                currentId = state.currentConversationId,
                onSelectWorkspace = { viewModel.selectWorkspace(it) },
                onCreateWorkspace = { name, prompt -> viewModel.createWorkspace(name, prompt) },
                onSelect = {
                    viewModel.selectConversation(it)
                    if (closeAfterAction) scope.launch { drawerState.close() }
                },
                onNew = {
                    viewModel.newConversation()
                    if (closeAfterAction) scope.launch { drawerState.close() }
                },
                onDelete = { viewModel.deleteConversation(it) },
                onTogglePin = { viewModel.togglePin(it) },
                onToggleArchive = { viewModel.toggleArchive(it) },
                onSearch = { viewModel.searchConversations(it) },
                onSettings = {
                    if (closeAfterAction) scope.launch { drawerState.close() }
                    onSettings()
                }
            )
        }

        @Composable
        fun ChatWorkspace(showDrawerToggle: Boolean) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            val convId = state.currentConversationId
                            val title = state.conversations.find { it.id == convId }?.title
                            Text(title ?: "FatAI", maxLines = 1)
                        },
                        navigationIcon = {
                            if (showDrawerToggle) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        FeatherIcons.Menu,
                                        contentDescription = stringResource(Res.string.open_conversations)
                                    )
                                }
                            }
                        },
                        actions = {
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Text(
                                    state.activeProvider.displayName,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                            IconButton(onClick = { viewModel.newConversation() }) {
                                Icon(
                                    FeatherIcons.Plus,
                                    contentDescription = stringResource(Res.string.new_conversation)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    if (state.currentConversationId == null) {
                        WelcomeScreen(
                            onNewChat = { viewModel.newConversation() },
                            providerName = state.activeProvider.displayName
                        )
                    } else {
                        ChatMessagesArea(
                            messages = state.messages,
                            messageAttachments = state.messageAttachments,
                            isStreaming = state.isStreaming,
                            assistantActivity = state.assistantActivity,
                            scrollPosition = state.chatScrollPosition,
                            onScrollPositionChange = viewModel::updateChatScrollPosition,
                            modifier = Modifier.weight(1f)
                        )
                        HorizontalDivider()
                        ChatInputBar(
                            text = state.inputText,
                            onTextChange = { viewModel.updateInputText(it) },
                            onSend = { viewModel.sendMessage(analyzeAttachedFilePrompt) },
                            isStreaming = state.isStreaming,
                            onStop = { viewModel.stopGeneration() },
                            onRegenerate = { viewModel.regenerate() },
                            onContinue = { viewModel.continueGeneration() },
                            attachments = state.attachments,
                            onAttach = { filePicker.launch() },
                            onRemoveAttachment = { viewModel.removeAttachment(it) },
                            enabled = state.activeConfig != null,
                            modifier = Modifier.navigationBarsPadding()
                        )
                    }
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compactLayout = maxWidth < 840.dp
            if (compactLayout) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.width(288.dp),
                            drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            drawerContentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Sidebar(closeAfterAction = true)
                        }
                    }
                ) {
                    ChatWorkspace(showDrawerToggle = true)
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(288.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Sidebar(closeAfterAction = false)
                    }
                    VerticalDivider()
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ChatWorkspace(showDrawerToggle = false)
                    }
                }
            }
        }

        if (showApiKeyGuide) {
            ApiKeySetupDialog(
                onConfigure = {
                    showApiKeyGuide = false
                    onSettings()
                }
            )
        }
    }
}

@Composable
private fun ApiKeySetupDialog(onConfigure: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.add_api_key)) },
        text = { Text(stringResource(Res.string.add_api_key_description)) },
        confirmButton = {
            Button(onClick = onConfigure) { Text(stringResource(Res.string.add_key)) }
        }
    )
}

@Composable
private fun WelcomeScreen(onNewChat: () -> Unit, providerName: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "F",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(Res.string.welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.welcome_provider, providerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Card(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(stringResource(Res.string.welcome_start_title), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.welcome_start_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onNewChat,
                    shape = RoundedCornerShape(10.dp)
                ) { Text(stringResource(Res.string.new_chat)) }
            }
        }
    }
}

@Composable
private fun ConversationSidebar(
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
            singleLine = true
        )

        val sorted = conversations.sortedWith(
            compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sorted, key = { it.id }) { conv ->
                val isSelected = conv.id == currentId
                var showMenu by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .clickable { onSelect(conv.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (conv.isPinned) "\uD83D\uDCCC ${conv.title}" else conv.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Text(
                                "${conv.providerType.displayName}  ${conv.model}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    onClick = { onTogglePin(conv.id); showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.archive)) },
                                    onClick = { onToggleArchive(conv.id); showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(Res.string.delete),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = { showMenu = false; showDeleteDialog = conv.id }
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onSettings),
            shape = RoundedCornerShape(12.dp),
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

@Composable
private fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.new_workspace)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(Res.string.workspace_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    label = { Text(stringResource(Res.string.workspace_instruction)) },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, prompt) }, enabled = name.isNotBlank()) {
                Text(
                    stringResource(Res.string.create)
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } }
    )
}

@Composable
private fun ChatMessagesArea(
    messages: List<ai.fatai.repo.ChatItem>,
    messageAttachments: Map<String, List<FileAsset>>,
    isStreaming: Boolean,
    assistantActivity: AssistantActivity?,
    scrollPosition: ChatScrollPosition,
    onScrollPositionChange: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val conversationId = messages.firstOrNull()?.conversationId
    // The list also contains one spacer before and after the messages.
    val lastListItemIndex = messages.size + 1
    val restoredIndex = scrollPosition.firstVisibleItemIndex
        .coerceIn(0, lastListItemIndex)
    val restoredOffset = scrollPosition.firstVisibleItemScrollOffset.coerceAtLeast(0)
    val canRestorePosition =
        scrollPosition.hasSavedPosition && scrollPosition.conversationId == conversationId
    val listState = remember(conversationId) {
        LazyListState(restoredIndex, restoredOffset)
    }
    val scrollScope = rememberCoroutineScope()
    var hasInitializedPosition by remember(conversationId) { mutableStateOf(false) }

    DisposableEffect(listState, conversationId) {
        onDispose {
            conversationId?.let { id ->
                onScrollPositionChange(
                    id,
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset
                )
            }
        }
    }

    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.content, isStreaming) {
        if (!hasInitializedPosition) {
            hasInitializedPosition = true
            if (!canRestorePosition && messages.isNotEmpty()) {
                listState.scrollToItem(lastListItemIndex, Int.MAX_VALUE)
            }
        } else if (isStreaming && messages.isNotEmpty()) {
            // A streaming response can become taller than the viewport. Scroll to the end of
            // its item (instead of only its start) after every new chunk so the newest text
            // remains visible. Using an immediate scroll prevents high-frequency chunks from
            // continually cancelling and restarting scroll animations.
            listState.scrollToItem(lastListItemIndex, Int.MAX_VALUE)
        }
    }

    val showBackToBottomButton = listState.firstVisibleItemIndex < lastListItemIndex - 3

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compactLayout = maxWidth < 600.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = if (compactLayout) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactLayout) 12.dp else 18.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(messages, key = { it.id }) { msg ->
                ChatBubble(
                    msg = msg,
                    showThinking = isStreaming && msg.id == messages.lastOrNull()?.id,
                    assistantActivity = assistantActivity,
                    compactLayout = compactLayout,
                    attachments = messageAttachments[msg.id].orEmpty()
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        PlatformListScrollbar(
            state = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
        )
        if (showBackToBottomButton) {
            FloatingActionButton(
                onClick = {
                    scrollScope.launch {
                        // This is a quick-jump control. An animated scroll reaches the trailing
                        // spacer first, then snaps again when the button disappears at the bottom.
                        listState.scrollToItem(lastListItemIndex, Int.MAX_VALUE)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = if (compactLayout) 16.dp else 24.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(FeatherIcons.ChevronDown, stringResource(Res.string.back_to_bottom))
            }
        }
    }
}

@Composable
private fun ChatBubble(
    msg: ai.fatai.repo.ChatItem,
    showThinking: Boolean,
    assistantActivity: AssistantActivity?,
    compactLayout: Boolean,
    attachments: List<FileAsset>
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
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (isQuestion) 14.dp else 6.dp,
                    bottomEnd = if (isQuestion) 6.dp else 14.dp
                )
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
                                    compactLayout = compactLayout
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
                                    lineHeight = lineHeight
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
                        MessageAttachments(attachments)
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

        if (isQuestion) {
            Spacer(Modifier.width(if (compactLayout) 6.dp else 8.dp))
            Box(
                modifier = Modifier.size(avatarSize).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center
            ) {
                Text("U", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSources(sources: List<MessageSource>) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = source.url != null) {
                                showAll = false
                                source.url?.let(::openUrl)
                            }
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

@Composable
private fun MessageAttachments(attachments: List<FileAsset>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { asset ->
            if (asset.mimeType.startsWith("image/", ignoreCase = true)) {
                FileKitAsyncImage(
                    file = PlatformFile(asset.localPath),
                    contentDescription = asset.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { openFileWithSystemApplication(asset.localPath, asset.mimeType) }
                )
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
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityIndicator(activity: AssistantActivity?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(7.dp))
        Text(
            stringResource(
                when (activity) {
                    AssistantActivity.Searching -> Res.string.searching
                    AssistantActivity.CheckingWeather -> Res.string.checking_weather
                    AssistantActivity.UsingTool -> Res.string.using_tool
                    else -> Res.string.thinking
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isStreaming: Boolean,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    attachments: List<FileAsset>,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (attachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                items(attachments, key = { it.id }) { asset ->
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
                                onClick = { onRemoveAttachment(asset.id) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                            ) {
                                Text("×")
                            }
                        }
                    }
                }
            }
        }
        if (isStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onStop) {
                    Icon(
                        FeatherIcons.Square,
                        stringResource(Res.string.stop),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(Res.string.stop))
                }
            }
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
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .desktopSendOnEnter(
                        enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty()) && !isStreaming,
                        onSend = onSend
                    ),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onAttach, enabled = enabled && !isStreaming) {
                            Icon(FeatherIcons.Paperclip, stringResource(Res.string.attach_file))
                        }
                        if (isStreaming) {
                            IconButton(onClick = onStop) {
                                Icon(FeatherIcons.Square, stringResource(Res.string.stop))
                            }
                        } else {
                            IconButton(
                                onClick = onSend,
                                enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty())
                            ) {
                                Icon(FeatherIcons.Send, stringResource(Res.string.send))
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4
            )
        }
        if (!isStreaming) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onRegenerate) {
                    Icon(
                        FeatherIcons.RefreshCw,
                        stringResource(Res.string.regenerate),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(Res.string.regenerate), fontSize = 12.sp)
                }
                TextButton(onClick = onContinue) {
                    Text(
                        stringResource(Res.string.continue_generation),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
