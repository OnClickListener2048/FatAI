package ai.fatai.ai.chat

import ai.fatai.ai.PlatformListScrollbar
import ai.fatai.bean.ChatItemType
import ai.fatai.feature.files.FileAsset
import ai.fatai.repo.ChatItem
import ai.fatai.viewmodel.AssistantActivity
import ai.fatai.viewmodel.ChatScrollPosition
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import fatai.composeapp.generated.resources.Res
import fatai.composeapp.generated.resources.back_to_bottom
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Scrollable list of message bubbles with scroll-position restore, auto-scroll while
 * streaming, a platform scrollbar, and a "back to bottom" jump button.
 */
@Composable
internal fun ChatMessagesArea(
    messages: List<ChatItem>,
    messageAttachments: Map<String, List<FileAsset>>,
    isStreaming: Boolean,
    assistantActivity: AssistantActivity?,
    scrollPosition: ChatScrollPosition,
    onScrollPositionChange: (String, Int, Int) -> Unit,
    onRegenerate: () -> Unit,
    onDownloadAttachment: (FileAsset) -> Unit,
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
                .widthIn(max = 820.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = if (compactLayout) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactLayout) 12.dp else 16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(messages, key = { it.id }) { msg ->
                ChatBubble(
                    msg = msg,
                    showThinking = isStreaming && msg.id == messages.lastOrNull()?.id,
                    assistantActivity = assistantActivity,
                    compactLayout = compactLayout,
                    attachments = messageAttachments[msg.id].orEmpty(),
                    showRegenerate = !isStreaming && msg.type == ChatItemType.Answer &&
                        msg.id == messages.lastOrNull()?.id,
                    onRegenerate = onRegenerate,
                    onDownloadAttachment = onDownloadAttachment
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
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(end = if (compactLayout) 16.dp else 24.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(FeatherIcons.ChevronDown, stringResource(Res.string.back_to_bottom))
            }
        }
    }
}
