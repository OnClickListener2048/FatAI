package ai.fatai.ai.chat

import ai.fatai.viewmodel.AssistantActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.checking_weather
import fatai.composeapp.generated.resources.searching
import fatai.composeapp.generated.resources.thinking
import fatai.composeapp.generated.resources.using_tool
import org.jetbrains.compose.resources.stringResource

/** Small spinner with a label describing what the assistant is currently doing. */
@Composable
internal fun ActivityIndicator(activity: AssistantActivity?) {
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
