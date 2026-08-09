package ai.fatai.ai.chat

import ai.fatai.viewmodel.UploadProgress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.UploadCloud

/** Attachment chip shown while the file is being uploaded, with a live progress bar. */
@Composable
internal fun UploadProgressChip(upload: UploadProgress) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Icon(
                FeatherIcons.UploadCloud,
                contentDescription = null,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    upload.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 130.dp)
                )
                LinearProgressIndicator(
                    progress = { upload.fraction },
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .widthIn(max = 130.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "${(upload.fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
