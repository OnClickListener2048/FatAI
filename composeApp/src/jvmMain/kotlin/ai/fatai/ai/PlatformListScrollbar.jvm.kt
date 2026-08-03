package ai.fatai.ai

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

@Composable
actual fun PlatformListScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(
        adapter = remember(state) { StableLazyListScrollbarAdapter(state) },
        modifier = modifier
    )
}

/**
 * Chat rows can vary from a one-line message to a long Markdown document. The stock adapter
 * estimates content size from currently visible row heights, causing its thumb to resize while
 * scrolling. This adapter uses stable item coordinates so the thumb stays the same size.
 */
private class StableLazyListScrollbarAdapter(
    private val state: LazyListState
) : ScrollbarAdapter {
    private val totalItems: Int
        get() = state.layoutInfo.totalItemsCount

    override val scrollOffset: Double
        get() {
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0.0
            val progressInItem = if (first.size == 0) 0.0 else (-first.offset / first.size.toDouble())
            val maxOffset = (contentSize - viewportSize).coerceAtLeast(0.0)
            return (first.index + progressInItem).coerceIn(0.0, maxOffset)
        }

    override val contentSize: Double
        get() = totalItems.coerceAtLeast(1).toDouble()

    override val viewportSize: Double
        get() = if (totalItems > 0) 1.0 else 0.0

    override suspend fun scrollTo(scrollOffset: Double) {
        if (totalItems == 0) return
        state.scrollToItem(scrollOffset.roundToInt().coerceIn(0, totalItems - 1))
    }
}
