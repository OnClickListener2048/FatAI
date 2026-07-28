package ai.fatai.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Renders full GFM Markdown by appending only the newly received SSE text to the renderer state.
 */
@Composable
internal fun MarkdownMessage(
    markdown: String,
    messageId: String,
    chunkFlow: Flow<String>,
    compactLayout: Boolean,
    modifier: Modifier = Modifier
) {
    key(messageId) {
        val markdownState = rememberStreamingMarkdownState()
        val initialMarkdown = remember(messageId) { markdown }
        val streamingChunks = remember(messageId, chunkFlow) {
            flow {
                if (initialMarkdown.isNotEmpty()) emit(initialMarkdown)
                emitAll(chunkFlow)
            }
        }

        LaunchedEffect(streamingChunks, markdownState) {
            streamingChunks.collect { chunk ->
                if (chunk.isNotEmpty()) markdownState.append(chunk)
            }
        }

        Markdown(
            streamingMarkdownState = markdownState,
            modifier = modifier,
            typography = markdownTypography(compactLayout)
        )
    }
}

@Composable
private fun markdownTypography(compactLayout: Boolean) = markdownTypography(
    h1 = MaterialTheme.typography.headlineSmall.copy(
        fontSize = if (compactLayout) 22.sp else 28.sp,
        lineHeight = if (compactLayout) 28.sp else 34.sp
    ),
    h2 = MaterialTheme.typography.titleLarge.copy(
        fontSize = if (compactLayout) 19.sp else 23.sp,
        lineHeight = if (compactLayout) 25.sp else 30.sp
    ),
    h3 = MaterialTheme.typography.titleMedium.copy(
        fontSize = if (compactLayout) 17.sp else 19.sp,
        lineHeight = if (compactLayout) 23.sp else 26.sp
    ),
    h4 = MaterialTheme.typography.titleMedium.copy(fontSize = if (compactLayout) 16.sp else 18.sp),
    h5 = MaterialTheme.typography.titleSmall.copy(fontSize = if (compactLayout) 15.sp else 16.sp),
    h6 = MaterialTheme.typography.titleSmall.copy(fontSize = if (compactLayout) 14.sp else 15.sp),
    text = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    ),
    paragraph = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    ),
    ordered = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    ),
    bullet = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    ),
    list = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    ),
    quote = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    ),
    table = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    ),
    code = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = if (compactLayout) 13.sp else 14.sp
    ),
    inlineCode = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = if (compactLayout) 13.sp else 14.sp
    ),
    textLink = TextLinkStyles(
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline
        ).toSpanStyle()
    )
)
