package ai.fatai.ai.chat

import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.files.FileAssetService
import ai.fatai.feature.user.User
import ai.fatai.feature.user.UserRepository
import ai.fatai.repo.ApiKeyRepository
import ai.fatai.theme.OpenWebUISwitch
import ai.fatai.viewmodel.AIChatViewModel
import ai.fatai.viewmodel.ChatScreenState
import ai.fatai.viewmodel.SaveAttachmentRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import compose.icons.FeatherIcons
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Plus
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.analyze_attached_file
import fatai.composeapp.generated.resources.attach_file
import fatai.composeapp.generated.resources.new_conversation
import fatai.composeapp.generated.resources.open_conversations
import fatai.composeapp.generated.resources.thinking_mode
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Root chat screen: responsive sidebar + workspace routing. Kept as a thin entry point —
 * the navigation layer calls [ai.fatai.ai.AIChatScreen], which delegates here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ChatScreen(onSettings: () -> Unit) {
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
    // The system back gesture/button closes the open drawer before reaching the app exit.
    androidx.compose.ui.backhandler.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // Server-backed attachment images load straight from the attachment URL with the Bearer
    // header; Coil caches the result (memory + disk), so items that a LazyColumn disposed on
    // scroll are restored from the cache instead of re-downloading the bytes. Rows synced
    // from other devices carry the server-stored URL; locally attached rows fall back to the
    // derived `GET /v1/files/{file_id}` URL for servers that predate the url column.
    val fileAssetService = koinInject<FileAssetService>()
    val platformContext = LocalPlatformContext.current
    val attachmentImageRequest: suspend (FileAsset) -> ImageRequest = { asset ->
        ImageRequest.Builder(platformContext)
            .data(asset.url.ifBlank { "${fileAssetService.serverBaseUrl}/v1/files/${asset.id}" })
            .httpHeaders(
                NetworkHeaders.Builder()
                    .add("Authorization", "Bearer ${fileAssetService.accessTokenProvider()}")
                    .build()
            )
            .build()
    }
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
                sizeBytes = file.size(),
                readBytes = { file.readBytes() }
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    // Attachment downloads end in the platform save dialog: the ViewModel streams the bytes
    // here and the (deprecated) byte-based launch writes them through the platform saver
    // (Android SAF, JVM file dialog, iOS document picker) — one uniform flow on all platforms.
    val saveLauncher = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault()
    ) { saved ->
        if (saved != null) {
            scope.launch { snackbarHostState.showSnackbar("Attachment saved: ${saved.name}") }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.saveAttachmentRequests.collect { request ->
            val (baseName, extension) = saveFileNameParts(request.displayName, request.mimeType)
            @Suppress("DEPRECATION")
            saveLauncher.launch(
                bytes = request.bytes,
                baseName = baseName,
                extension = extension
            )
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
                        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        drawerContentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        ChatSidebar(
                            state = state,
                            user = user,
                            closeAfterAction = true,
                            viewModel = viewModel,
                            drawerState = drawerState,
                            scope = scope,
                            onSettings = onSettings
                        )
                    }
                }
            ) {
                ChatWorkspace(
                    state = state,
                    showDrawerToggle = true,
                    viewModel = viewModel,
                    drawerState = drawerState,
                    scope = scope,
                    snackbarHostState = snackbarHostState,
                    onAttach = { filePicker.launch() },
                    sendPrompt = analyzeAttachedFilePrompt,
                    onDownloadAttachment = viewModel::downloadAttachment,
                    onImageRequest = attachmentImageRequest
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(288.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    ChatSidebar(
                        state = state,
                        user = user,
                        closeAfterAction = false,
                        viewModel = viewModel,
                        drawerState = drawerState,
                        scope = scope,
                        onSettings = onSettings
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ChatWorkspace(
                        state = state,
                        showDrawerToggle = false,
                        viewModel = viewModel,
                        drawerState = drawerState,
                        scope = scope,
                        snackbarHostState = snackbarHostState,
                        onAttach = { filePicker.launch() },
                        sendPrompt = analyzeAttachedFilePrompt,
                        onDownloadAttachment = viewModel::downloadAttachment,
                        onImageRequest = attachmentImageRequest
                    )
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

/** Sidebar with drawer-aware close-on-action behavior for the compact layout. */
@Composable
internal fun ChatSidebar(
    state: ChatScreenState,
    user: User,
    closeAfterAction: Boolean,
    viewModel: AIChatViewModel,
    drawerState: DrawerState,
    scope: CoroutineScope,
    onSettings: () -> Unit
) {
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

/** Main chat column: top bar, message list, and input bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatWorkspace(
    state: ChatScreenState,
    showDrawerToggle: Boolean,
    viewModel: AIChatViewModel,
    drawerState: DrawerState,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onAttach: () -> Unit,
    sendPrompt: String,
    onDownloadAttachment: (FileAsset) -> Unit,
    onImageRequest: suspend (FileAsset) -> ImageRequest
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
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
                    if (state.activeProvider.supportsThinkingMode && state.activeConfig != null) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.clickable { viewModel.setThinkingEnabled(!state.activeConfig!!.thinkingEnabled) }
                        ) {
                            Text(
                                stringResource(Res.string.thinking_mode),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                            OpenWebUISwitch(
                                checked = state.activeConfig!!.thinkingEnabled,
                                onCheckedChange = { viewModel.setThinkingEnabled(it) },
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
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
                .pointerInput(Unit) {
                    // Tapping anywhere outside the text field dismisses the soft
                    // keyboard; interactive children consume their own taps.
                    detectTapGestures(onTap = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    })
                }
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
                    onRegenerate = viewModel::regenerate,
                    onDownloadAttachment = onDownloadAttachment,
                    onImageRequest = onImageRequest,
                    modifier = Modifier.weight(1f)
                )
                HorizontalDivider()
                ChatInputBar(
                    text = state.inputText,
                    onTextChange = { viewModel.updateInputText(it) },
                    onSend = { viewModel.sendMessage(sendPrompt) },
                    isStreaming = state.isStreaming,
                    onStop = { viewModel.stopGeneration() },
                    attachments = state.attachments,
                    uploads = state.uploads,
                    onAttach = onAttach,
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    enabled = state.activeConfig != null,
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }
    }
}

/**
 * Splits a display name into a base name and a file extension for the save dialog. The
 * extension is never null and never duplicated: "report.pdf" yields ("report", "pdf");
 * a name without an extension falls back to the MIME type mapping, then to "bin".
 */
private fun saveFileNameParts(displayName: String, mimeType: String): Pair<String, String> {
    val dotIndex = displayName.lastIndexOf('.')
    if (dotIndex > 0) {
        val base = displayName.substring(0, dotIndex)
        val extension = displayName.substring(dotIndex + 1).ifBlank { extensionFor(mimeType) }
        return base to extension
    }
    return displayName to extensionFor(mimeType)
}

private fun extensionFor(mimeType: String): String = when (mimeType.lowercase().substringBefore(';')) {
    "application/pdf" -> "pdf"
    "application/msword" -> "doc"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/vnd.ms-excel" -> "xls"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
    "text/markdown" -> "md"
    "text/plain" -> "txt"
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    else -> "bin"
}
