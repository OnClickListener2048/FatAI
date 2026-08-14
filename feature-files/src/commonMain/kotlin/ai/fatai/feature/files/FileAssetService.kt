package ai.fatai.feature.files

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val DEFAULT_FILE_SERVER_URL = "http://127.0.0.1:8080"

/**
 * Uploads user-selected files to the FatAI server (S3-like object storage semantics).
 *
 * The server stores the bytes under a user-scoped directory and returns the asset id;
 * every later operation (document conversion, knowledge indexing) references that id,
 * so the client never exposes local file paths to the server.
 */
class FileAssetService(
    private val client: HttpClient,
    private val serverUrl: String = DEFAULT_FILE_SERVER_URL,
    private val accessToken: suspend () -> String
) {
    /** Server base URL without a trailing slash; platform download paths build URLs from it. */
    val serverBaseUrl: String get() = serverUrl.trimEnd('/')

    /** Fresh Bearer token provider, shared with the platform download paths. */
    val accessTokenProvider: suspend () -> String get() = accessToken
    /**
     * @param onProgress reports uploaded bytes; `total` is the request content length and may
     * be 0 when the engine cannot determine it.
     */
    suspend fun upload(
        fileName: String,
        mimeType: String,
        content: ByteArray,
        workspaceId: String?,
        conversationId: String?,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): UploadedFile {
        val response = client.submitFormWithBinaryData(
            url = "${serverUrl.trimEnd('/')}/v1/files",
            formData = formData {
                append("file", content, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                    append(HttpHeaders.ContentType, mimeType)
                })
            }
        ) {
            header("Authorization", "Bearer ${accessToken()}")
            workspaceId?.let { parameter("workspace_id", it) }
            conversationId?.let { parameter("conversation_id", it) }
            onUpload { bytesSentTotal, contentLength ->
                onProgress(bytesSentTotal, contentLength ?: 0L)
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw FileUploadException(
                response.status.value,
                body.ifBlank { "File upload failed with HTTP ${response.status.value}." }
            )
        }
        return json.decodeFromString<UploadedFile>(body)
    }

    /**
     * Downloads the original bytes of an uploaded attachment (`GET /v1/files/{file_id}`).
     *
     * Used by the desktop/iOS save-dialog paths; Android hands the URL to the system
     * DownloadManager instead and never fetches the bytes in-app.
     *
     * @param onProgress reports received bytes; `total` may be 0 when unknown.
     */
    suspend fun download(
        fileId: String,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): ByteArray {
        val response = client.get("$serverBaseUrl/v1/files/$fileId") {
            header("Authorization", "Bearer ${accessToken()}")
            onDownload { bytesReceivedTotal, contentLength ->
                onProgress(bytesReceivedTotal, contentLength ?: 0L)
            }
        }
        if (!response.status.isSuccess()) {
            throw FileUploadException(
                response.status.value,
                "File download failed with HTTP ${response.status.value}."
            )
        }
        return response.bodyAsBytes()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class UploadedFile(
    val id: String,
    @SerialName("display_name") val displayName: String = ""
)

class FileUploadException(val httpStatus: Int, message: String) : RuntimeException(message)
