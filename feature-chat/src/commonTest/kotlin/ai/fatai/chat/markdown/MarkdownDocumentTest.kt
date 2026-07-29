package ai.fatai.chat.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownDocumentTest {

    @Test
    fun parsesAndPersistsSupportedMarkdownBlocks() {
        val markdown = """
            # Heading **bold**

            > A quoted [link](https://example.com)

            - [x] completed
            - [ ] pending

            | Name | Value |
            | --- | --- |
            | `one` | ~~two~~ |

            ```kotlin
            println("hello")
            ```
        """.trimIndent()

        val document = MarkdownParser.parse(markdown)

        assertEquals(5, document.blocks.size)
        assertIs<MarkdownBlock.Heading>(document.blocks[0])
        assertIs<MarkdownBlock.Quote>(document.blocks[1])
        assertIs<MarkdownBlock.BulletList>(document.blocks[2])
        assertIs<MarkdownBlock.Table>(document.blocks[3])
        assertIs<MarkdownBlock.CodeBlock>(document.blocks[4])

        val restored = MarkdownDocumentCodec.decode(MarkdownDocumentCodec.encode(document))
        assertEquals(document, restored)
    }
}
