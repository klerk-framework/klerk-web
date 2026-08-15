package dev.klerkframework.web.upload

import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.web.Csrf
import dev.klerkframework.web.WebSupport
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.util.Base64

private val log = KotlinLogging.logger {}

/** The version of the tus protocol these routes speak. */
private const val TUS_VERSION = "1.0.0"

private const val TUS_RESUMABLE = "Tus-Resumable"
private const val UPLOAD_OFFSET = "Upload-Offset"
private const val UPLOAD_LENGTH = "Upload-Length"
private const val UPLOAD_METADATA = "Upload-Metadata"
private const val UPLOAD_CHECKSUM = "Upload-Checksum"
private const val TUS_EXTENSION = "Tus-Extension"
private const val TUS_MAX_SIZE = "Tus-Max-Size"
private const val OFFSET_OCTET_STREAM = "application/offset+octet-stream"

/**
 * Resumable upload endpoints, speaking [tus 1.0](https://tus.io/protocols/resumable-upload).
 *
 * ```kotlin
 * routing {
 *     uploadRoutes(support, uploadPlugin)
 * }
 * ```
 *
 * The protocol is deliberately the standard one, so that a browser can use an off-the-shelf client — though the
 * script klerk-web ships with its forms is enough for `file()` fields and needs no dependency.
 *
 * | | |
 * |---|---|
 * | `POST {path}` | starts an upload; `Upload-Length` and `Upload-Metadata` describe it. Responds `201` with `Location`. |
 * | `HEAD {path}/{id}` | the current `Upload-Offset`, i.e. where to resume. |
 * | `PATCH {path}/{id}` | appends bytes at `Upload-Offset`. `409` if that is not where the upload is. |
 * | `DELETE {path}/{id}` | discards the upload and its bytes. |
 *
 * Every request that changes something carries the ordinary klerk-web CSRF token, and an upload may only be
 * continued by the actor that created it. An upload that does not exist and one belonging to somebody else give the
 * same `404`, so the endpoints cannot be used to find out which uploads exist.
 *
 * @param path where the endpoints are mounted. Remembered by the plugin, so that forms know where to send files.
 * @param maxSize the largest upload these routes will start, advertised as `Tus-Max-Size`. A limit that depends on
 * who is asking belongs in an authorization rule on [CreateUpload] instead.
 */
public fun <C : KlerkContext, V> Route.uploadRoutes(
    support: WebSupport<C, V>,
    plugin: UploadPlugin<C, V>,
    path: String = "/uploads",
    maxSize: Long = 1024L * 1024 * 1024,
) {
    plugin.mountedAt = path

    options(path) {
        call.response.header(TUS_RESUMABLE, TUS_VERSION)
        call.response.header("Tus-Version", TUS_VERSION)
        call.response.header(TUS_EXTENSION, "creation,creation-with-upload,termination,checksum")
        call.response.header("Tus-Checksum-Algorithm", "sha256")
        call.response.header(TUS_MAX_SIZE, maxSize.toString())
        call.respond(HttpStatusCode.NoContent)
    }

    post(path) {
        val context = support.contextProvider(call, support.klerk)
        if (!isCsrfValid(call)) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val length = call.request.header(UPLOAD_LENGTH)?.toLongOrNull()
        if (length == null || length < 0 || length > maxSize) {
            call.respond(HttpStatusCode.BadRequest, "Upload-Length must be between 0 and $maxSize")
            return@post
        }
        val metadata = parseUploadMetadata(call.request.header(UPLOAD_METADATA))
        val id = try {
            plugin.create(
                context = context,
                filename = metadata["filename"]?.take(255) ?: "upload",
                declaredContentType = metadata["contentType"]?.take(255) ?: ContentType.Application.OctetStream.toString(),
                declaredSize = length,
            )
        } catch (e: AuthorizationException) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }

        // creation-with-upload: a small file needs no second round trip.
        var offset = 0L
        if (call.request.contentType().match(OFFSET_OCTET_STREAM)) {
            offset = withContext(Dispatchers.IO) {
                plugin.append(context, id, 0, call.receiveChannel().toInputStream())
            }
            call.response.header(UPLOAD_OFFSET, offset.toString())
        }

        call.response.header(TUS_RESUMABLE, TUS_VERSION)
        call.response.header(HttpHeaders.Location, "$path/${id.value}")
        call.respond(HttpStatusCode.Created)
    }

    head("$path/{id}") {
        val context = support.contextProvider(call, support.klerk)
        val id = call.uploadId() ?: return@head call.respond(HttpStatusCode.NotFound)
        val upload = try {
            plugin.get(context, id)
        } catch (e: NoSuchUploadException) {
            call.respond(HttpStatusCode.NotFound)
            return@head
        }
        call.response.header(TUS_RESUMABLE, TUS_VERSION)
        call.response.header(UPLOAD_OFFSET, upload.props.receivedBytes.valueWithoutAuthorization.toString())
        call.response.header(UPLOAD_LENGTH, upload.props.declaredSize.valueWithoutAuthorization.toString())
        // The offset must never be taken from a cache: it is the one thing a resuming client has to get right.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.NoContent)
    }

    patch("$path/{id}") {
        val context = support.contextProvider(call, support.klerk)
        if (!isCsrfValid(call)) {
            call.respond(HttpStatusCode.Forbidden)
            return@patch
        }
        if (!call.request.contentType().match(OFFSET_OCTET_STREAM)) {
            call.respond(HttpStatusCode.UnsupportedMediaType, "Content-Type must be $OFFSET_OCTET_STREAM")
            return@patch
        }
        val id = call.uploadId() ?: return@patch call.respond(HttpStatusCode.NotFound)
        val offset = call.request.header(UPLOAD_OFFSET)?.toLongOrNull()
        if (offset == null || offset < 0) {
            call.respond(HttpStatusCode.BadRequest, "Upload-Offset is required")
            return@patch
        }

        val checksum = call.request.header(UPLOAD_CHECKSUM)
        try {
            val body = call.receiveChannel().toInputStream()
            val newOffset = withContext(Dispatchers.IO) {
                if (checksum == null) {
                    plugin.append(context, id, offset, body)
                } else {
                    plugin.appendVerified(context, id, offset, body, checksum)
                }
            }
            call.response.header(TUS_RESUMABLE, TUS_VERSION)
            call.response.header(UPLOAD_OFFSET, newOffset.toString())
            call.respond(HttpStatusCode.NoContent)
        } catch (e: NoSuchUploadException) {
            call.respond(HttpStatusCode.NotFound)
        } catch (e: OffsetMismatch) {
            // tus says 409: the client should HEAD for the real offset and continue from there.
            call.response.header(UPLOAD_OFFSET, e.actual.toString())
            call.respond(HttpStatusCode.Conflict)
        } catch (e: ChecksumMismatch) {
            // 460 is the status tus reserves for a chunk that arrived corrupted.
            call.respond(HttpStatusCode(460, "Checksum Mismatch"))
        } catch (e: TooManyBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
        }
    }

    delete("$path/{id}") {
        val context = support.contextProvider(call, support.klerk)
        if (!isCsrfValid(call)) {
            call.respond(HttpStatusCode.Forbidden)
            return@delete
        }
        val id = call.uploadId() ?: return@delete call.respond(HttpStatusCode.NotFound)
        try {
            plugin.delete(context, id)
            call.response.header(TUS_RESUMABLE, TUS_VERSION)
            call.respond(HttpStatusCode.NoContent)
        } catch (e: NoSuchUploadException) {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

private fun ApplicationCall.uploadId(): ModelID<Upload>? =
    parameters["id"]?.toIntOrNull()?.let { ModelID(it) }

/**
 * The CSRF token, sent as a header rather than a form field because these requests have no form.
 *
 * The cookie is `SameSite=Strict`, so a cross-site request never carries it and can never match — but the token is
 * checked anyway, since "the cookie would not be there" is not something to rely on alone.
 */
private fun isCsrfValid(call: ApplicationCall): Boolean =
    Csrf.isValid(call, call.request.header(Csrf.TOKEN_NAME))

/**
 * `Upload-Metadata` is a comma-separated list of `key base64(value)` pairs.
 *
 * Everything in it comes from the client and is treated as a claim, never as fact: the filename is only ever shown
 * back to the user, and the content type is re-derived from the bytes before anything is served.
 */
internal fun parseUploadMetadata(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) {
        return emptyMap()
    }
    return header.split(",").mapNotNull { pair ->
        val parts = pair.trim().split(" ", limit = 2)
        val key = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val value = parts.getOrNull(1) ?: return@mapNotNull key to ""
        try {
            key to String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            log.debug { "Ignoring Upload-Metadata entry '$key' with a value that is not Base64" }
            null
        }
    }.toMap()
}
