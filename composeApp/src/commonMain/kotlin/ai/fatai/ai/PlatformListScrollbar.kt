package ai.fatai.ai

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Renders a native scrollbar on platforms where Compose exposes one. */
@Composable
expect fun PlatformListScrollbar(state: LazyListState, modifier: Modifier = Modifier)
