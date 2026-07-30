package ai.fatai.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private const val DEFAULT_DOCLING_SERVER_URL = "http://127.0.0.1:5001"

@Serializable
data class DoclingDocumentReadRequest(
    val localPath: String,
    val displayName: String,
    val mimeType: String
)

@Serializable
data class DoclingDocumentReadResponse(
    val displayName: String,
    val markdown: String
)

class DoclingDocumentService(
    private val client: HttpClient,
    private val doclingServerUrl: String = System.getenv("DOCLING_SERVER_URL") ?: DEFAULT_DOCLING_SERVER_URL,
    private val logFullDocumentContent: Boolean = System.getenv("DOCLING_LOG_FULL_CONTENT") == "true"
) {
    suspend fun read(request: DoclingDocumentReadRequest): DoclingDocumentReadResponse {
        val displayName = request.displayName.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("displayName must not be blank")
        val source = request.localPath.trim().takeIf(String::isNotEmpty)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: throw IllegalArgumentException("localPath must not be blank")

        require(source.isRegularFile()) { "The selected file does not exist or is not a regular file." }
        require(source.fileSize() <= MAX_FILE_SIZE_BYTES) { "The selected file exceeds the 50 MB limit." }
        val sourceSize = source.fileSize()
        logger.info(
            "Docling read requested: file={}, mimeType={}, bytes={}, endpoint={}",
            displayName,
            request.mimeType,
            sourceSize,
            doclingServerUrl
        )

        val startedAt = System.nanoTime()
        val response = try {
            client.submitFormWithBinaryData(
                url = "${doclingServerUrl.trimEnd('/')}/v1/convert/file",
                formData = formData {
                    append("files", Files.readAllBytes(source), Headers.build {
                        append(HttpHeaders.ContentType, request.mimeType.ifBlank { ContentType.Application.OctetStream.toString() })
                        append(HttpHeaders.ContentDisposition, ContentDisposition.File.withParameter("filename", displayName).toString())
                    })
                    append("to_formats", "md")
                    append("image_export_mode", "placeholder")
                    append("do_ocr", "true")
                }
            )
        } catch (exception: Exception) {
            logger.warn("Docling request failed: file={}, message={}", displayName, exception.message)
            throw DoclingDocumentException("DOCLING_UNAVAILABLE", "Docling Serve is unavailable at $doclingServerUrl.")
        }

        val body = response.bodyAsText()
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        logger.info(
            "Docling response received: file={}, httpStatus={}, elapsedMs={}, responseChars={}",
            displayName,
            response.status.value,
            elapsedMillis,
            body.length
        )
        if (!response.status.isSuccess()) {
            logger.warn(
                "Docling conversion failed: file={}, httpStatus={}, responsePreview={}",
                displayName,
                response.status.value,
                body.logValue(includeFullContent = false)
            )
            throw DoclingDocumentException("DOCLING_FAILED", "Docling returned ${response.status.value} while reading $displayName.")
        }

        val payload = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw DoclingDocumentException("DOCLING_FAILED", "Docling returned an invalid conversion response.")
        }
        val markdown = payload["document"]
            ?.jsonObject
            ?.get("md_content")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
        if (markdown.isNullOrBlank()) {
            val error = payload["errors"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("error_message")
                ?.jsonPrimitive
                ?.contentOrNull
            throw DoclingDocumentException("DOCLING_FAILED", error ?: "Docling did not return Markdown for $displayName.")
        }
        logger.info(
            "Docling Markdown ready: file={}, markdownChars={}, content={}",
            displayName,
            markdown.length,
            markdown.logValue(logFullDocumentContent)
        )
        return DoclingDocumentReadResponse(displayName, markdown)
    }

    private companion object {
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val json = Json { ignoreUnknownKeys = true }
        val logger = LoggerFactory.getLogger(DoclingDocumentService::class.java)
    }
}

class DoclingDocumentException(val code: String, message: String) : RuntimeException(message)

private fun String.logValue(includeFullContent: Boolean): String =
    replace(Regex("\\s+"), " ").trim().let { value ->
        if (includeFullContent) value else value.take(500)
    }
