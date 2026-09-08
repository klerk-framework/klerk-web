package dev.klerkframework.web.attached

import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedDataKind
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.web.image.ImagePlugin
import dev.klerkframework.web.WebSupport
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.*
import java.io.InputStream
import kotlin.time.Duration.Companion.days

/** Ktor has no constant for this header. */
private const val CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

/** How long a public value may be cached by default.
 * Using 28 days makes it easier to comply with 'right to be forgotten' laws, like the EU's GDPR.
 */
private val publicMaxAge = 28.days.inWholeSeconds

/**
 * What [WebSupport.attachedDataCacheControl] uses unless you replace it: `Public` data is cached for 28 days and
 * never revalidated within them, `Private` data is not cached at all.
 *
 * `no-store` for private data is the safe default rather than the only reasonable one. The bytes at a
 * hash-addressed URL cannot change, so what a cached copy outlives is not the content but the permission: an actor
 * whose `readAttachedData` rule stops matching, or who has logged out, goes on seeing a cached value without a
 * request ever reaching the server. `private, max-age=300, immutable` trades a revocation window of that length for not
 * re-fetching every thumbnail on every page view.
 */
public val defaultAttachedDataCacheControl: (AttachedDataMetadata) -> String = { metadata ->
    when (metadata.visibility) {
        AttachedDataVisibility.Public -> "public, max-age=$publicMaxAge, immutable"
        AttachedDataVisibility.Private -> "private, no-store"
    }
}

/**
 * The content types klerk-web renders inline. Everything else is served as a download.
 *
 * Only formats a browser cannot be talked into executing are here. SVG and HTML are deliberately absent: served
 * inline from your own origin, they are a script running as your application.
 */
public val defaultInlineContentTypes: Set<String> = setOf(
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
    "image/avif",
)

/**
 * Serves attached data (blobs and strings) over HTTP.
 *
 * ```kotlin
 * routing {
 *     attachedDataRoutes(support)
 * }
 * ```
 *
 * | | |
 * |---|---|
 * | `GET {path}/{id}/{hash}` | the value |
 * | `GET {path}/{id}/{hash}/{filename}` | the same; the last segment is ignored, so a download can have a human name |
 *
 * Build the URLs with [dev.klerkframework.web.PathProvider.attachedDataPath]. The hash is required: ids are recycled
 * once the data they referred to has been deleted, so an id alone would let a cache serve one value under another
 * value's URL. A request whose hash does not match the data is a `404`.
 *
 * Reading is authorized by Klerk's own `readAttachedData` rules, evaluated against the model that owns the value,
 * without reading a single byte. Data that does not exist, a hash that does not match, and data the actor may not
 * read all give the same `404`, so the route cannot be used to find out what exists.
 *
 * Every response's `Cache-Control` comes from [WebSupport.attachedDataCacheControl], which is handed the value's
 * metadata. A value is served inline only if
 * Klerk recognised its bytes as one of [inlineContentTypes]; anything else is `application/octet-stream` with
 * `Content-Disposition: attachment`, and `X-Content-Type-Options: nosniff` is always sent.
 *
 * Install Ktor's `PartialContent` plugin to have range requests served.
 *
 * @param path where the route is mounted.
 * @param inlineContentTypes what may be rendered inline rather than downloaded. Adding a type here means you have
 * decided the browser may do whatever it likes with it, in your application's origin.
 * @param serveOriginalImages whether the file as it was uploaded may be fetched, for the types [images] handles.
 * Off by default: an uploaded photograph knows where it was taken, when, on what, and often carries a thumbnail of
 * an earlier crop of itself, none of which a variant keeps. With this off, an image is reachable only through a
 * template, and only the sizes that template declared. Turn it on when the original is the point — a photographer
 * downloading their own work — and strip the file at upload with
 * [dev.klerkframework.web.image.ImagePlugin.prepareImage] when you do. Ignored when [images] is null, since without
 * the plugin there are no variants to serve instead.
 */
public fun <C : KlerkContext, V> Route.attachedDataRoutes(
    support: WebSupport<C, V>,
    images: ImagePlugin<C, V>? = null,
    path: String = support.pathProvider.attachedDataBase,
    inlineContentTypes: Set<String> = defaultInlineContentTypes,
    serveOriginalImages: Boolean = false,
) {
    val settings = ServingSettings(inlineContentTypes, images, serveOriginalImages)
    get("$path/{id}/{hash}") { serve(support, settings) }
    get("$path/{id}/{hash}/{filename}") { serve(support, settings) }
}

private class ServingSettings<C : KlerkContext, V>(
    val inlineContentTypes: Set<String>,
    val images: ImagePlugin<C, V>?,
    val serveOriginalImages: Boolean,
)

private suspend fun <C : KlerkContext, V> RoutingContext.serve(
    support: WebSupport<C, V>,
    settings: ServingSettings<C, V>,
) {
    val id = AttachedDataID.parse(call.parameters["id"])
    if (id == null) {
        call.respond(HttpStatusCode.BadRequest)
        return
    }
    val context = support.contextProvider(call, support.klerk)
    // The kind is not in the URL, so the metadata is what says whether this is a blob or a string.
    val metadata = try {
        support.klerk.attachedData.getMetadata(id, context)
    } catch (e: NoSuchElementException) {
        null
    } catch (e: AuthorizationException) {
        null
    }
    if (metadata == null || metadata.hash != call.parameters["hash"]) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val segment = call.parameters["filename"]
    val images = settings.images
    if (images != null && segment != null && images.handles(metadata.contentType) && images.looksLikeVariant(segment)) {
        // A size outside the allow-list is a 404 rather than a download: the number of images that can ever be
        // generated has to stay finite.
        val variant = images.parseVariant(segment)
        if (variant == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        serveVariant(images, id, metadata, variant, support, context)
        return
    }

    if (images != null && images.handles(metadata.contentType) && !settings.serveOriginalImages) {
        // The original is the copy that still knows where the photograph was taken. Only variants are served, and
        // this is a 404 like every other thing this route will not give you.
        call.respond(HttpStatusCode.NotFound)
        return
    }

    // The value may have been deleted since the metadata was read, in which case it is a 404 like any other. Nothing
    // is written to the response before this succeeds, so a 404 never carries the value's caching headers.
    val stream = try {
        when (metadata.kind) {
            AttachedDataKind.Blob -> support.klerk.attachedData.get(id.asBlob(), context)
            AttachedDataKind.String -> support.klerk.attachedData.getStream(id.asString(), context)
        }
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound)
        return
    } catch (e: AuthorizationException) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val inline = metadata.contentType != null && settings.inlineContentTypes.contains(metadata.contentType)
    call.response.header(CONTENT_TYPE_OPTIONS, "nosniff")
    call.response.header(HttpHeaders.CacheControl, support.attachedDataCacheControl(metadata))
    if (!inline) {
        call.response.header(HttpHeaders.ContentDisposition, attachment(call.parameters["filename"]))
    }

    val contentType = if (inline) {
        ContentType.parse(requireNotNull(metadata.contentType))
    } else {
        ContentType.Application.OctetStream
    }
    call.respond(AttachedDataContent(stream, contentType, metadata.size))
}

/**
 * Serves a generated image.
 *
 * A variant that does not exist yet is scheduled and waited for, so that what comes back under a variant's URL is
 * always that variant. Nothing stands in for it: an original served under a small variant's URL is the wrong shape,
 * the wrong size, and — for an image no processor will ever accept — an unbounded amount of egress on every request.
 *
 * A variant that has already been refused is a `404` at once. One that is still not there when the wait runs out is
 * a `503`, so the client is told to come back rather than shown something else.
 */
private suspend fun <C : KlerkContext, V> RoutingContext.serveVariant(
    images: ImagePlugin<C, V>,
    id: AttachedDataID,
    metadata: AttachedDataMetadata,
    variant: ImagePlugin.RequestedVariant,
    support: WebSupport<C, V>,
    context: C,
) {
    call.response.header(CONTENT_TYPE_OPTIONS, "nosniff")

    // Refused once means refused forever, so do not make the caller wait to find that out again.
    if (images.refused("${id.value}", metadata.hash, variant)) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val generated = images.existing("${id.value}", metadata.hash, variant)
        ?: run {
            images.requestVariant("${id.value}", metadata.hash, variant)
            images.awaitVariant("${id.value}", metadata.hash, variant)
        }

    if (generated == null) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (images.refused("${id.value}", metadata.hash, variant)) {
            // Refused while we waited: it is never going to exist.
            call.respond(HttpStatusCode.NotFound)
        } else {
            // The render queue has not got to it yet. Worth retrying, not worth caching.
            images.recordUnavailable()
            call.response.header(HttpHeaders.RetryAfter, "1")
            call.respond(HttpStatusCode.ServiceUnavailable)
        }
        return
    }

    call.response.header(HttpHeaders.CacheControl, support.attachedDataCacheControl(metadata))
    call.respond(LocalFileContent(generated.toFile(), ContentType.parse(variant.contentType)))
}

/** The filename comes from the URL, so only characters that cannot break out of the header survive. */
private fun attachment(filename: String?): String {
    val safe = filename?.filter { it.isLetterOrDigit() || it in "-_. " }?.take(100)?.trim()
    return if (safe.isNullOrEmpty()) {
        ContentDisposition.Attachment.toString()
    } else {
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, safe).toString()
    }
}

/**
 * Streams the value with its length known in advance, which is what lets Ktor's `PartialContent` plugin serve range
 * requests. The stream is closed when the channel is exhausted or cancelled.
 */
private class AttachedDataContent(
    private val stream: InputStream,
    override val contentType: ContentType,
    override val contentLength: Long,
) : OutgoingContent.ReadChannelContent() {
    override fun readFrom(): ByteReadChannel = stream.toByteReadChannel()
}
