package ai.fatai.ai.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.cancel
import fatai.composeapp.generated.resources.create
import fatai.composeapp.generated.resources.new_workspace
import fatai.composeapp.generated.resources.workspace_instruction
import fatai.composeapp.generated.resources.workspace_name
import org.jetbrains.compose.resources.stringResource

/** Dialog for creating a new workspace with an optional instruction prompt. */
@Composable
internal fun CreateWorkspaceDialog(
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
