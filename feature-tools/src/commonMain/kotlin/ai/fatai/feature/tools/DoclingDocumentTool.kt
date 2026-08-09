package ai.fatai.feature.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.header
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
 *
 * Two input modes are supported:
 * - `file_id`: the file was uploaded to the server first (S3-like reference semantics); the
 *   server reads the stored bytes itself, so the request carries the Bearer token.
 * - `local_path`: legacy desktop-migration mode where the server reads the user's disk directly
 *   (only works while the server runs with ALLOW_LOCAL_DOCUMENT_PATHS=true).
 */
class DoclingDocumentTool(
    private val client: HttpClient,
    private val serverUrl: String = DEFAULT_TOOL_SERVER_URL,
    private val accessTokenProvider: (suspend () -> String)? = null
) : Tool {
    override val isModelCallable: Boolean = false

    override val definition = ToolDefinition(
        name = "docling_document_read",
        displayName = "Document reader",
        description = "Extracts Markdown from a user-selected document or image using Docling.",
        parameters = listOf(
            ToolParameter("file_id", "Server-stored file id of the user-selected document.", false),
            ToolParameter("local_path", "Path of a file explicitly selected by the user.", false),
            ToolParameter("display_name", "Original filename shown to the user.", true),
            ToolParameter("mime_type", "MIME type of the selected file.", true)
        )
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val fileId = arguments["file_id"]?.trim()
        val localPath = arguments["local_path"]?.trim()
        val displayName = arguments.getValue("display_name").trim()
        val mimeType = arguments.getValue("mime_type").trim()
        if ((fileId.isNullOrBlank() && localPath.isNullOrBlank()) || displayName.isBlank() || mimeType.isBlank()) {
            return ToolResult.Failure(
                "INVALID_ARGUMENT",
                "Either file_id or local_path plus a filename and MIME type are required."
            )
        }

        val response = try {
            if (fileId != null) {
                // S3-like reference semantics: the server reads the stored bytes by id.
                val token = accessTokenProvider?.invoke()
                if (token == null) {
                    return ToolResult.Failure(
                        "DOCLING_UNAVAILABLE",
                        "Authentication is required for server-side document reads."
                    )
                }
                client.post("${serverUrl.trimEnd('/')}/v1/files/$fileId/read") {
                    header("Authorization", "Bearer $token")
                }
            } else {
                // Legacy desktop-migration mode: the server reads the user's disk directly.
                client.post("${serverUrl.trimEnd('/')}/v1/tools/document-read") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DoclingDocumentLocalPathRequest(localPath!!, displayName, mimeType)))
                }
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
private data class DoclingDocumentLocalPathRequest(
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
