package ai.fatai.chat.markdown

/**
 * Stable, application-owned Markdown render model.
 *
 * It intentionally contains only data that can be persisted and rendered on every platform,
 * rather than a parser-library AST that disappears when the process is restarted.
 */
data class MarkdownDocument(
    val blocks: List<MarkdownBlock>
)

sealed interface MarkdownBlock {
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Quote(val content: List<MarkdownInline>) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletList(val items: List<MarkdownListItem>) : MarkdownBlock
    data class OrderedList(val start: Int, val items: List<MarkdownListItem>) : MarkdownBlock
    data class Table(
        val header: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>
    ) : MarkdownBlock

    data object HorizontalRule : MarkdownBlock
}

data class MarkdownListItem(
    val content: List<MarkdownInline>,
    /** null means a normal list item; true/false represents a GFM task item. */
    val checked: Boolean? = null
)

sealed interface MarkdownInline {
    data class Text(val value: String) : MarkdownInline
    data class Bold(val content: List<MarkdownInline>) : MarkdownInline
    data class Italic(val content: List<MarkdownInline>) : MarkdownInline
    data class Strikethrough(val content: List<MarkdownInline>) : MarkdownInline
    data class Code(val value: String) : MarkdownInline
    data class Link(val content: List<MarkdownInline>, val url: String) : MarkdownInline
}

object MarkdownParser {
    private val headingPattern = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private val bulletPattern = Regex("^\\s*[-*+]\\s+(.+)$")
    private val orderedPattern = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
    private val taskPattern = Regex("^\\[([ xX])]\\s+(.+)$")

    fun parse(markdown: String): MarkdownDocument {
        if (markdown.isBlank()) return MarkdownDocument(emptyList())

        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }

            if (line.trimStart().startsWith("```")) {
                val language = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    codeLines += lines[index]
                    index++
                }
                if (index < lines.size) index++
                blocks += MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n"))
                continue
            }

            val heading = headingPattern.matchEntire(line)
            if (heading != null) {
                blocks += MarkdownBlock.Heading(
                    level = heading.groupValues[1].length,
                    content = parseInlines(heading.groupValues[2])
                )
                index++
                continue
            }

            if (isHorizontalRule(line)) {
                blocks += MarkdownBlock.HorizontalRule
                index++
                continue
            }

            if (isTableStart(lines, index)) {
                val header = splitTableRow(lines[index]).map(::parseInlines)
                index += 2 // Header separator determines that this is a table.
                val rows = mutableListOf<List<List<MarkdownInline>>>()
                while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                    val cells = splitTableRow(lines[index]).map(::parseInlines).toMutableList()
                    while (cells.size < header.size) cells.add(emptyList())
                    rows += cells.take(header.size)
                    index++
                }
                blocks += MarkdownBlock.Table(header, rows)
                continue
            }

            if (line.trimStart().startsWith('>')) {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith('>')) {
                    quoteLines += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                    index++
                }
                blocks += MarkdownBlock.Quote(parseInlines(quoteLines.joinToString("\n")))
                continue
            }

            bulletPattern.matchEntire(line)?.let {
                val items = mutableListOf<MarkdownListItem>()
                while (index < lines.size) {
                    val itemMatch = bulletPattern.matchEntire(lines[index]) ?: break
                    items += parseListItem(itemMatch.groupValues[1])
                    index++
                }
                blocks += MarkdownBlock.BulletList(items)
                continue
            }

            orderedPattern.matchEntire(line)?.let { first ->
                val items = mutableListOf<MarkdownListItem>()
                val start = first.groupValues[1].toIntOrNull() ?: 1
                while (index < lines.size) {
                    val itemMatch = orderedPattern.matchEntire(lines[index]) ?: break
                    items += parseListItem(itemMatch.groupValues[2])
                    index++
                }
                blocks += MarkdownBlock.OrderedList(start, items)
                continue
            }

            val paragraphLines = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines, index)) {
                paragraphLines += lines[index]
                index++
            }
            if (paragraphLines.isEmpty()) {
                // An unsupported block marker should still remain visible as ordinary text.
                paragraphLines += lines[index]
                index++
            }
            blocks += MarkdownBlock.Paragraph(parseInlines(paragraphLines.joinToString("\n")))
        }

        return MarkdownDocument(blocks)
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return line.trimStart().startsWith("```") ||
            headingPattern.matches(line) ||
            isHorizontalRule(line) ||
            isTableStart(lines, index) ||
            line.trimStart().startsWith('>') ||
            bulletPattern.matches(line) ||
            orderedPattern.matches(line)
    }

    private fun parseListItem(value: String): MarkdownListItem {
        val task = taskPattern.matchEntire(value)
        return if (task != null) {
            MarkdownListItem(
                content = parseInlines(task.groupValues[2]),
                checked = task.groupValues[1].equals("x", ignoreCase = true)
            )
        } else {
            MarkdownListItem(parseInlines(value))
        }
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size || !lines[index].contains('|')) return false
        val dividerCells = splitTableRow(lines[index + 1])
        return dividerCells.isNotEmpty() && dividerCells.all { cell ->
            cell.trim().matches(Regex("^:?-{3,}:?$"))
        }
    }

    private fun splitTableRow(line: String): List<String> {
        val value = line.trim().removePrefix("|").removeSuffix("|")
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        value.forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        if (escaped) current.append('\\')
        cells += current.toString().trim()
        return cells
    }

    private fun isHorizontalRule(line: String): Boolean {
        val marker = line.filterNot(Char::isWhitespace)
        return marker.length >= 3 && marker.all { it == '-' || it == '*' || it == '_' } &&
            marker.all { it == marker.first() }
    }

    fun parseInlines(source: String): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        val text = StringBuilder()

        fun appendText(value: String) {
            if (value.isEmpty()) return
            val previous = result.lastOrNull()
            if (previous is MarkdownInline.Text) {
                result[result.lastIndex] = MarkdownInline.Text(previous.value + value)
            } else {
                result += MarkdownInline.Text(value)
            }
        }

        fun flushText() {
            appendText(text.toString())
            text.clear()
        }

        var index = 0
        while (index < source.length) {
            val character = source[index]
            if (character == '\\' && index + 1 < source.length) {
                text.append(source[index + 1])
                index += 2
                continue
            }

            if (character == '`') {
                val closing = source.indexOf('`', index + 1)
                if (closing > index + 1) {
                    flushText()
                    result += MarkdownInline.Code(source.substring(index + 1, closing))
                    index = closing + 1
                    continue
                }
            }

            val linkStart = if (character == '!' && source.getOrNull(index + 1) == '[') index + 1 else index
            if (source.getOrNull(linkStart) == '[') {
                val labelEnd = source.indexOf(']', linkStart + 1)
                val urlStart = if (labelEnd >= 0 && source.getOrNull(labelEnd + 1) == '(') labelEnd + 2 else -1
                val urlEnd = if (urlStart >= 0) source.indexOf(')', urlStart) else -1
                if (labelEnd > linkStart && urlEnd >= urlStart) {
                    flushText()
                    result += MarkdownInline.Link(
                        content = parseInlines(source.substring(linkStart + 1, labelEnd)),
                        url = source.substring(urlStart, urlEnd)
                    )
                    index = urlEnd + 1
                    continue
                }
            }

            val marker = when {
                source.startsWith("**", index) -> "**"
                source.startsWith("__", index) -> "__"
                source.startsWith("~~", index) -> "~~"
                character == '*' -> "*"
                character == '_' -> "_"
                else -> null
            }
            if (marker != null) {
                val closing = source.indexOf(marker, index + marker.length)
                if (closing > index + marker.length) {
                    flushText()
                    val content = parseInlines(source.substring(index + marker.length, closing))
                    result += when (marker) {
                        "**", "__" -> MarkdownInline.Bold(content)
                        "~~" -> MarkdownInline.Strikethrough(content)
                        else -> MarkdownInline.Italic(content)
                    }
                    index = closing + marker.length
                    continue
                }
                if (index + marker.length < source.length) {
                    // During streaming the closing marker has not arrived yet. Render the
                    // remainder in its eventual style immediately instead of changing the
                    // entire run of text after the closing marker arrives.
                    flushText()
                    val content = parseInlines(source.substring(index + marker.length))
                    result += when (marker) {
                        "**", "__" -> MarkdownInline.Bold(content)
                        "~~" -> MarkdownInline.Strikethrough(content)
                        else -> MarkdownInline.Italic(content)
                    }
                    break
                }
            }

            text.append(character)
            index++
        }
        flushText()
        return result
    }
}

/** Length-prefixed codec avoids a parser-library dependency and is safe for arbitrary Markdown text. */
object MarkdownDocumentCodec {
    private const val HEADER = "FATAI_MD_1"

    fun encode(document: MarkdownDocument): String = buildString {
        token(HEADER)
        token(document.blocks.size)
        document.blocks.forEach { block -> writeBlock(block) }
    }

    fun decode(value: String): MarkdownDocument? = runCatching {
        val reader = Reader(value)
        require(reader.token() == HEADER)
        MarkdownDocument(List(reader.int()) { reader.readBlock() }).also { reader.requireFinished() }
    }.getOrNull()

    private fun StringBuilder.writeBlock(block: MarkdownBlock) {
        when (block) {
            is MarkdownBlock.Paragraph -> {
                token("P")
                writeInlines(block.content)
            }
            is MarkdownBlock.Heading -> {
                token("H")
                token(block.level)
                writeInlines(block.content)
            }
            is MarkdownBlock.Quote -> {
                token("Q")
                writeInlines(block.content)
            }
            is MarkdownBlock.CodeBlock -> {
                token("C")
                string(block.language)
                string(block.code)
            }
            is MarkdownBlock.BulletList -> {
                token("U")
                writeListItems(block.items)
            }
            is MarkdownBlock.OrderedList -> {
                token("O")
                token(block.start)
                writeListItems(block.items)
            }
            is MarkdownBlock.Table -> {
                token("T")
                writeCells(block.header)
                token(block.rows.size)
                block.rows.forEach { cells -> writeCells(cells) }
            }
            MarkdownBlock.HorizontalRule -> token("R")
        }
    }

    private fun StringBuilder.writeListItems(items: List<MarkdownListItem>) {
        token(items.size)
        items.forEach { item ->
            token(
                when (item.checked) {
                    null -> "N"
                    true -> "Y"
                    false -> "X"
                }
            )
            writeInlines(item.content)
        }
    }

    private fun StringBuilder.writeCells(cells: List<List<MarkdownInline>>) {
        token(cells.size)
        cells.forEach { inlines -> writeInlines(inlines) }
    }

    private fun StringBuilder.writeInlines(inlines: List<MarkdownInline>) {
        token(inlines.size)
        inlines.forEach { inline ->
            when (inline) {
                is MarkdownInline.Text -> {
                    token("T")
                    string(inline.value)
                }
                is MarkdownInline.Bold -> {
                    token("B")
                    writeInlines(inline.content)
                }
                is MarkdownInline.Italic -> {
                    token("I")
                    writeInlines(inline.content)
                }
                is MarkdownInline.Strikethrough -> {
                    token("S")
                    writeInlines(inline.content)
                }
                is MarkdownInline.Code -> {
                    token("C")
                    string(inline.value)
                }
                is MarkdownInline.Link -> {
                    token("L")
                    writeInlines(inline.content)
                    string(inline.url)
                }
            }
        }
    }

    private fun StringBuilder.token(value: Any) {
        append(value).append(';')
    }

    private fun StringBuilder.string(value: String) {
        append(value.length).append(':').append(value)
    }

    private class Reader(private val value: String) {
        private var index = 0

        fun token(): String {
            val end = value.indexOf(';', index)
            require(end >= index)
            return value.substring(index, end).also { index = end + 1 }
        }

        fun int(): Int = token().toInt()

        fun string(): String {
            val lengthEnd = value.indexOf(':', index)
            require(lengthEnd >= index)
            val length = value.substring(index, lengthEnd).toInt()
            index = lengthEnd + 1
            require(index + length <= value.length)
            return value.substring(index, index + length).also { index += length }
        }

        fun readBlock(): MarkdownBlock = when (token()) {
            "P" -> MarkdownBlock.Paragraph(readInlines())
            "H" -> MarkdownBlock.Heading(int(), readInlines())
            "Q" -> MarkdownBlock.Quote(readInlines())
            "C" -> MarkdownBlock.CodeBlock(string(), string())
            "U" -> MarkdownBlock.BulletList(readListItems())
            "O" -> MarkdownBlock.OrderedList(int(), readListItems())
            "T" -> {
                val header = readCells()
                MarkdownBlock.Table(header, List(int()) { readCells() })
            }
            "R" -> MarkdownBlock.HorizontalRule
            else -> error("Unknown markdown block")
        }

        private fun readListItems(): List<MarkdownListItem> = List(int()) {
            val checked = when (token()) {
                "N" -> null
                "Y" -> true
                "X" -> false
                else -> error("Unknown task state")
            }
            MarkdownListItem(readInlines(), checked)
        }

        private fun readCells(): List<List<MarkdownInline>> = List(int()) { readInlines() }

        private fun readInlines(): List<MarkdownInline> = List(int()) {
            when (token()) {
                "T" -> MarkdownInline.Text(string())
                "B" -> MarkdownInline.Bold(readInlines())
                "I" -> MarkdownInline.Italic(readInlines())
                "S" -> MarkdownInline.Strikethrough(readInlines())
                "C" -> MarkdownInline.Code(string())
                "L" -> MarkdownInline.Link(readInlines(), string())
                else -> error("Unknown markdown inline")
            }
        }

        fun requireFinished() = require(index == value.length)
    }
}
