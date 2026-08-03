package ai.fatai.chat.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownParserTest {
    @Test
    fun unmatchedBoldMarkerStylesStreamedTextImmediately() {
        val document = MarkdownParser.parse("Before **streamed")

        assertEquals(
            listOf(
                MarkdownInline.Text("Before "),
                MarkdownInline.Bold(listOf(MarkdownInline.Text("streamed")))
            ),
            (document.blocks.single() as MarkdownBlock.Paragraph).content
        )
    }
}
