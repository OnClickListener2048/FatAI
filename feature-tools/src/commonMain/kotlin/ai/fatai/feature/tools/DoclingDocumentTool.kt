package ai.fatai.feature.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DEFAULT_TOOL_SERVER_URL = "http://127.0.0.1:8080"

/**
 * Converts a user-selected document or image through the local Docling service.
 *
 * The application invokes this tool for chat attachments. It is deliberately not advertised to
 * the model: a model must never be allowed to choose arbitrary local file paths.
 */
class DoclingDocumentTool(
    private val client: HttpClient,
    private val serverUrl: String = DEFAULT_TOOL_SERVER_URL
) : Tool {
    override val isModelCallable: Boolean = false

    override val definition = ToolDefinition(
        name = "docling_document_read",
        displayName = "Document reader",
        description = "Extracts Markdown from a user-selected document or image using Docling.",
        parameters = listOf(
            ToolParameter("local_path", "Path of a file explicitly selected by the user.", true),
            ToolParameter("display_name", "Original filename shown to the user.", true),
            ToolParameter("mime_type", "MIME type of the selected file.", true)
        )
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val localPath = arguments.getValue("local_path").trim()
        val displayName = arguments.getValue("display_name").trim()
        val mimeType = arguments.getValue("mime_type").trim()
        if (localPath.isBlank() || displayName.isBlank() || mimeType.isBlank()) {
            return ToolResult.Failure("INVALID_ARGUMENT", "A file path, filename, and MIME type are required.")
        }

        val response = try {
            client.post("${serverUrl.trimEnd('/')}/v1/tools/document-read") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DoclingDocumentReadRequest(localPath, displayName, mimeType)))
            }
        } catch (_: Exception) {
            return ToolResult.Failure(
                "DOCLING_UNAVAILABLE",
                "The local FatAI or Docling server is unavailable. Start both services and try again."
            )
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<DoclingDocumentReadError>(body) }.getOrNull()
            return ToolResult.Failure(error?.code ?: "DOCLING_FAILED", error?.message ?: "Document conversion failed.")
        }
        val document = runCatching { json.decodeFromString<DoclingDocumentReadResponse>(body) }.getOrElse {
            return ToolResult.Failure("DOCLING_FAILED", "The local server returned an invalid document response.")
        }

        return ToolResult.Success(
            content = buildString {
                appendLine("Document: ${document.displayName}")
                appendLine("Extracted by Docling as Markdown:")
                appendLine()
                append(document.markdown)
            },
            sources = listOf(ToolSource(document.displayName))
        )
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class DoclingDocumentReadRequest(
    val localPath: String,
    val displayName: String,
    val mimeType: String
)

@Serializable
private data class DoclingDocumentReadResponse(
    val displayName: String,
    val markdown: String
)

@Serializable
private data class DoclingDocumentReadError(
    val code: String,
    val message: String
)
