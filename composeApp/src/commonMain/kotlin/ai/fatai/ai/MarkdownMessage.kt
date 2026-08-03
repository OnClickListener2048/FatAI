package ai.fatai.ai

import ai.fatai.chat.markdown.MarkdownBlock
import ai.fatai.chat.markdown.MarkdownDocument
import ai.fatai.chat.markdown.MarkdownInline
import ai.fatai.chat.markdown.MarkdownListItem
import ai.fatai.chat.markdown.MarkdownParser
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders the application-owned, persistable Markdown document model. */
@Composable
internal fun MarkdownMessage(
    markdown: String,
    document: MarkdownDocument?,
    compactLayout: Boolean,
    modifier: Modifier = Modifier
) {
    val parsedDocument = remember(markdown, document) { document ?: MarkdownParser.parse(markdown) }
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compactLayout) 14.sp else 15.sp,
        lineHeight = if (compactLayout) 20.sp else 22.sp
    )

    Column(modifier = modifier.fillMaxWidth()) {
        parsedDocument.blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.size(if (compactLayout) 7.dp else 10.dp))
            MarkdownBlockContent(block, bodyStyle, compactLayout)
        }
    }
}

@Composable
private fun MarkdownBlockContent(
    block: MarkdownBlock,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    compactLayout: Boolean
) {
    when (block) {
        is MarkdownBlock.Paragraph -> MarkdownRichText(block.content, bodyStyle)
        is MarkdownBlock.Heading -> MarkdownRichText(
            block.content,
            when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }.copy(
                fontSize = when (block.level) {
                    1 -> if (compactLayout) 22.sp else 28.sp
                    2 -> if (compactLayout) 19.sp else 23.sp
                    3 -> if (compactLayout) 17.sp else 19.sp
                    else -> if (compactLayout) 15.sp else 16.sp
                }
            )
        )
        is MarkdownBlock.Quote -> Row {
            Spacer(
                Modifier
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            MarkdownRichText(
                block.content,
                bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                Modifier.weight(1f)
            )
        }
        is MarkdownBlock.CodeBlock -> MarkdownCodeBlock(block, bodyStyle, compactLayout)
        is MarkdownBlock.BulletList -> MarkdownList(block.items, null, bodyStyle)
        is MarkdownBlock.OrderedList -> MarkdownList(block.items, block.start, bodyStyle)
        is MarkdownBlock.Table -> MarkdownTable(block, bodyStyle, compactLayout)
        MarkdownBlock.HorizontalRule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MarkdownCodeBlock(
    block: MarkdownBlock.CodeBlock,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    compactLayout: Boolean
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(scrollState)
            .padding(if (compactLayout) 10.dp else 12.dp)
    ) {
        if (block.language.isNotBlank()) {
            Text(
                block.language,
                style = bodyStyle.copy(
                    fontSize = if (compactLayout) 11.sp else 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.size(5.dp))
        }
        SelectionContainer {
            Text(
                block.code,
                style = bodyStyle.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compactLayout) 13.sp else 14.sp
                )
            )
        }
    }
}

@Composable
private fun MarkdownList(
    items: List<MarkdownListItem>,
    orderedStart: Int?,
    bodyStyle: androidx.compose.ui.text.TextStyle
) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.Top) {
                when (val checked = item.checked) {
                    null -> Text(
                        text = orderedStart?.let { "${it + index}." } ?: "•",
                        style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.width(if (orderedStart == null) 20.dp else 28.dp)
                    )
                    else -> Checkbox(
                        checked = checked == true,
                        onCheckedChange = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (item.checked != null) Spacer(Modifier.width(4.dp))
                MarkdownRichText(item.content, bodyStyle, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    compactLayout: Boolean
) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return

    val minimumCellWidth = if (compactLayout) 130.dp else 160.dp
    val scrollState = rememberScrollState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
    ) {
        val tableWidth = maxOf(maxWidth, minimumCellWidth * columns)
        val cellWidth = tableWidth / columns
        Box(Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
            Column(Modifier.width(tableWidth)) {
                MarkdownTableRow(
                    cells = table.header,
                    columns = columns,
                    cellWidth = cellWidth,
                    bodyStyle = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
                    background = MaterialTheme.colorScheme.surfaceVariant,
                    drawBottomDivider = true
                )
                table.rows.forEachIndexed { index, row ->
                    MarkdownTableRow(
                        cells = row,
                        columns = columns,
                        cellWidth = cellWidth,
                        bodyStyle = bodyStyle,
                        background = Color.Transparent,
                        drawBottomDivider = index < table.rows.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<List<MarkdownInline>>,
    columns: Int,
    cellWidth: androidx.compose.ui.unit.Dp,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    background: Color,
    drawBottomDivider: Boolean
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        repeat(columns) { index ->
            MarkdownRichText(
                inlines = cells.getOrElse(index) { emptyList() },
                bodyStyle = bodyStyle,
                modifier = Modifier
                    .widthIn(min = cellWidth, max = cellWidth)
                    .fillMaxHeight()
                    .background(background)
                    .tableCellDividers(
                        color = dividerColor,
                        drawRightDivider = index < columns - 1,
                        drawBottomDivider = drawBottomDivider
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

private fun Modifier.tableCellDividers(
    color: Color,
    drawRightDivider: Boolean,
    drawBottomDivider: Boolean
): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    if (drawRightDivider) {
        drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth)
    }
    if (drawBottomDivider) {
        drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth)
    }
}

@Composable
private fun MarkdownRichText(
    inlines: List<MarkdownInline>,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    SelectionContainer {
        Text(
            text = buildAnnotatedString { appendInlines(inlines, codeBackground, linkColor) },
            style = bodyStyle,
            modifier = modifier
        )
    }
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    codeBackground: Color,
    linkColor: Color
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.value)
            is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(inline.content, codeBackground, linkColor)
            }
            is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.content, codeBackground, linkColor)
            }
            is MarkdownInline.Strikethrough -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            ) {
                appendInlines(inline.content, codeBackground, linkColor)
            }
            is MarkdownInline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            ) {
                append(inline.value)
            }
            is MarkdownInline.Link -> {
                pushStringAnnotation(tag = "url", annotation = inline.url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    appendInlines(inline.content, codeBackground, linkColor)
                }
                pop()
            }
        }
    }
}
