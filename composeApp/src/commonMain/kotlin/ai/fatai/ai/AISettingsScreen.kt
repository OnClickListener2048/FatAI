package ai.fatai.ai

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.fatai.chat.ProviderType
import ai.fatai.repo.ApiKeyRepository
import ai.fatai.repo.ApiKeyInfo
import ai.fatai.feature.settings.SettingsRepository
import ai.fatai.feature.settings.ThemeMode
import ai.fatai.feature.memory.MemoryEntry
import ai.fatai.feature.memory.MemoryRepository
import ai.fatai.feature.memory.MemoryScope
import ai.fatai.theme.OpenWebUISwitch
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

class AISettingsScreen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(onBack: () -> Unit) {
        val apiKeyRepo = koinInject<ApiKeyRepository>()
        val settingsRepo = koinInject<SettingsRepository>()
        val memoryRepo = koinInject<MemoryRepository>()
        val scope = rememberCoroutineScope()
        var keys by remember { mutableStateOf(apiKeyRepo.getAllKeys()) }
        val themeMode by settingsRepo.themeMode.collectAsState()
        var showThemeDialog by remember { mutableStateOf(false) }
        var showAddDialog by remember { mutableStateOf(keys.isEmpty()) }
        var showDeleteDialog by remember { mutableStateOf<ApiKeyInfo?>(null) }
        var memories by remember { mutableStateOf(memoryRepo.getAll(MemoryScope.GLOBAL)) }
        var memoryEnabled by remember {
            mutableStateOf(settingsRepo.getValue("memory_enabled") != "false")
        }
        var showAddMemory by remember { mutableStateOf(false) }
        var showClearMemoryConfirmation by remember { mutableStateOf(false) }
        var editingMemory by remember { mutableStateOf<MemoryEntry?>(null) }
        var newMemoryContent by remember { mutableStateOf("") }
        var editMemoryContent by remember { mutableStateOf("") }

        fun refresh() {
            keys = apiKeyRepo.getAllKeys()
            memories = memoryRepo.getAll(MemoryScope.GLOBAL)
        }

        fun toggleMemory(enabled: Boolean) {
            memoryEnabled = enabled
            settingsRepo.putValue("memory_enabled", if (enabled) "true" else "false")
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.settings)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Text("\u2190", fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
            ) {
                Text(stringResource(Res.string.fatai_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.appearance_model_access), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(Res.string.appearance), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showThemeDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(Res.string.theme), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                themeModeLabel(themeMode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(stringResource(Res.string.personalization), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                // ---- Memory section ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Header with toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(Res.string.memory), fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(Res.string.memory_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            OpenWebUISwitch(
                                checked = memoryEnabled,
                                onCheckedChange = { toggleMemory(it) }
                            )
                        }

                        if (memoryEnabled) {
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Spacer(Modifier.height(10.dp))

                            // Add memory button
                            TextButton(onClick = { showAddMemory = true }) {
                                Icon(FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.memory_add))
                            }

                            if (memories.isEmpty()) {
                                Text(
                                    stringResource(Res.string.memory_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                Spacer(Modifier.height(4.dp))
                                // Memory list
                                memories.take(20).forEach { memory ->
                                    MemoryItemRow(
                                        memory = memory,
                                        conversationTitle = null,
                                        onEdit = {
                                            editingMemory = memory
                                            editMemoryContent = memory.content
                                        },
                                        onDelete = {
                                            memoryRepo.archive(memory.id)
                                            refresh()
                                        }
                                    )
                                }

                                // Clear all
                                if (memories.size > 3) {
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { showClearMemoryConfirmation = true }) {
                                        Text(
                                            stringResource(Res.string.memory_clear_all),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(Res.string.model_providers), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { showAddDialog = true }, shape = RoundedCornerShape(10.dp)) {
                        Text(stringResource(Res.string.add_key))
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (keys.isEmpty()) {
                    Text(
                        stringResource(Res.string.no_api_keys),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(keys, key = { it.id }) { key ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (key.isActive)
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(key.name, fontWeight = FontWeight.Bold)
                                        if (key.isActive) {
                                            Spacer(Modifier.width(8.dp))
                                            Text("\u2713", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Text(
                                        "${key.providerType.displayName}  ${key.model}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        key.baseUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (key.apiKey.isBlank()) {
                                            stringResource(Res.string.api_key_server_managed)
                                        } else {
                                            stringResource(
                                                Res.string.api_key_preview,
                                                key.apiKey.take(8),
                                                key.apiKey.takeLast(4)
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!key.isActive) {
                                    Button(onClick = {
                                        apiKeyRepo.setActiveKey(key.id)
                                        refresh()
                                    }) {
                                        Text(stringResource(Res.string.use))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                TextButton(onClick = { showDeleteDialog = key }) {
                                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AddApiKeyDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { providerType, name, apiKey, baseUrl, model ->
                    apiKeyRepo.addKey(providerType, name, apiKey, baseUrl, model)
                    showAddDialog = false
                    refresh()
                }
            )
        }

        if (showDeleteDialog != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                title = { Text(stringResource(Res.string.delete_api_key)) },
                text = { Text(stringResource(Res.string.delete_key_confirmation, showDeleteDialog!!.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        apiKeyRepo.deleteKey(showDeleteDialog!!.id)
                        showDeleteDialog = null
                        refresh()
                    }) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) { Text(stringResource(Res.string.cancel)) }
                }
            )
        }

        if (showThemeDialog) {
            ThemeSelectionDialog(
                selectedMode = themeMode,
                onDismiss = { showThemeDialog = false },
                onSelect = { mode -> settingsRepo.setThemeMode(mode) }
            )
        }

        if (showAddMemory) {
            AddMemoryDialog(
                content = newMemoryContent,
                onContentChange = { newMemoryContent = it },
                onDismiss = {
                    showAddMemory = false
                    newMemoryContent = ""
                },
                onSave = {
                    if (newMemoryContent.isNotBlank()) {
                        memoryRepo.save(newMemoryContent, MemoryScope.GLOBAL)
                        newMemoryContent = ""
                        showAddMemory = false
                        refresh()
                    }
                }
            )
        }

        if (editingMemory != null) {
            EditMemoryDialog(
                content = editMemoryContent,
                onContentChange = { editMemoryContent = it },
                onDismiss = {
                    editingMemory = null
                    editMemoryContent = ""
                },
                onSave = {
                    if (editMemoryContent.isNotBlank()) {
                        editingMemory?.let { memoryRepo.update(it.id, editMemoryContent) }
                        editingMemory = null
                        editMemoryContent = ""
                        refresh()
                    }
                }
            )
        }

        if (showClearMemoryConfirmation) {
            ClearMemoryDialog(
                onDismiss = { showClearMemoryConfirmation = false },
                onConfirm = {
                    memoryRepo.clearAll()
                    showClearMemoryConfirmation = false
                    refresh()
                }
            )
        }
    }
}

@Composable
private fun ThemeSelectionDialog(
    selectedMode: ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (ThemeMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(Res.string.theme)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val selected = mode == selectedMode
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onSelect(mode)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(themeModeLabel(mode), modifier = Modifier.weight(1f))
                            if (selected) {
                                Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}

@Composable
private fun MemoryItemRow(
    memory: MemoryEntry,
    conversationTitle: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                memory.content,
                style = MaterialTheme.typography.bodyMedium
            )
            if (conversationTitle != null) {
                Text(
                    stringResource(Res.string.memory_source, conversationTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(
            onClick = onEdit,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(stringResource(Res.string.edit), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = onDelete,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(stringResource(Res.string.delete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(Res.string.system)
    ThemeMode.LIGHT -> stringResource(Res.string.light)
    ThemeMode.DARK -> stringResource(Res.string.dark)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddApiKeyDialog(
    onDismiss: () -> Unit,
    onAdd: (ProviderType, String, String, String, String) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ProviderType.OpenAI) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(selectedProvider.defaultBaseUrl) }
    var model by remember { mutableStateOf(selectedProvider.defaultModel) }
    val defaultKeyName = stringResource(Res.string.default_key_name, selectedProvider.displayName)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dialogTapInteraction = remember { MutableInteractionSource() }
    val dismissKeyboard: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        Unit
    }

    AlertDialog(
        onDismissRequest = {
            dismissKeyboard()
            onDismiss()
        },
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.clickable(
            interactionSource = dialogTapInteraction,
            indication = null,
            onClick = dismissKeyboard
        ),
        title = {
            Column {
                Text(stringResource(Res.string.add_api_key), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.add_api_key_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(Res.string.provider)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        ProviderType.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    selectedProvider = provider
                                    baseUrl = provider.defaultBaseUrl
                                    model = provider.defaultModel
                                    providerMenuExpanded = false
                                    dismissKeyboard()
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.display_name)) },
                    placeholder = { Text(stringResource(Res.string.my_api_key)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { dismissKeyboard() })
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text(stringResource(Res.string.api_key_example)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { dismissKeyboard() })
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.base_url)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { dismissKeyboard() })
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.model)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { dismissKeyboard() })
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (apiKey.isNotBlank()) {
                        dismissKeyboard()
                        onAdd(
                            selectedProvider,
                            name.ifBlank { defaultKeyName },
                            apiKey,
                            baseUrl.ifBlank { selectedProvider.defaultBaseUrl },
                            model.ifBlank { selectedProvider.defaultModel }
                        )
                    }
                },
                enabled = apiKey.isNotBlank()
            ) { Text(stringResource(Res.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = {
                dismissKeyboard()
                onDismiss()
            }) { Text(stringResource(Res.string.cancel)) }
        }
    )
}

// ---- Memory dialogs (add, edit, clear confirmation) ----

@Composable
private fun AddMemoryDialog(
    content: String,
    onContentChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(Res.string.memory_add)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                placeholder = { Text(stringResource(Res.string.memory_add_hint)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 5
            )
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = content.isNotBlank()
            ) { Text(stringResource(Res.string.memory_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}

@Composable
private fun EditMemoryDialog(
    content: String,
    onContentChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(Res.string.edit)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 5
            )
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = content.isNotBlank()
            ) { Text(stringResource(Res.string.memory_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}

@Composable
private fun ClearMemoryDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(Res.string.memory_clear_all)) },
        text = { Text(stringResource(Res.string.memory_clear_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}
