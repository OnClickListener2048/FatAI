package ai.fatai.ai.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.add_api_key
import fatai.composeapp.generated.resources.add_api_key_description
import fatai.composeapp.generated.resources.add_key
import fatai.composeapp.generated.resources.new_chat
import fatai.composeapp.generated.resources.welcome_provider
import fatai.composeapp.generated.resources.welcome_start_body
import fatai.composeapp.generated.resources.welcome_start_title
import fatai.composeapp.generated.resources.welcome_title
import org.jetbrains.compose.resources.stringResource

/** Empty-state hero shown when no conversation is open yet. */
@Composable
internal fun WelcomeScreen(onNewChat: () -> Unit, providerName: String) {
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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

/** Prompt shown on first launch until an API key is configured. */
@Composable
internal fun ApiKeySetupDialog(onConfigure: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.add_api_key)) },
        text = { Text(stringResource(Res.string.add_api_key_description)) },
        confirmButton = {
            Button(onClick = onConfigure) { Text(stringResource(Res.string.add_key)) }
        }
    )
}
