package dev.klerkframework.web.image

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The size of an image, as read from it rather than as claimed by anybody. */
public data class ImageInfo(val width: Int, val height: Int) {
    public val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * What an [ImageProcessor] refuses, whatever else it can do.
 *
 * @param maxPixels the largest image that may be decoded, in pixels. Checked *before* decoding: a 1 kB PNG can
 * declare 100 megapixels, and decoding it is how a small file becomes a large heap.
 * @param timeout how long one render may take.
 */
public data class ImageLimits(
    val maxPixels: Long = 50_000_000,
    val timeout: Duration = 30.seconds,
)

/** Thrown when an image is larger than [ImageLimits.maxPixels], or is not an image at all. */
public class ImageRefused(message: String) : RuntimeException(message)

/**
 * Turns an original image into the variants klerk-web serves.
 *
 * klerk-web ships [ImageIoProcessor], which uses the JDK and is therefore limited to JPEG and PNG and decodes
 * attacker-supplied bytes inside your own JVM. It is the development answer. In production, implement this interface
 * against an image transformer running beside the application — its own process, its own container, no network
 * egress — so that a decoder bug is contained and modern formats are available. See
 * [the images documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/images.md) for the recipe.
 *
 * Both methods are given a file on the local filesystem, and [render] writes one: an implementation talking to a
 * sidecar shares a directory with it, which is what [ImagePlugin]'s `stagingDirectory` is for.
 *
 * ### What an implementation must honour
 *
 * **[limits] are yours to enforce.** Nothing checks them for you, and [ImageLimits.maxPixels] is what stands between
 * a 1 kB file and an exhausted heap.
 *
 * **Nothing you write may carry the source's metadata.** A variant and a stripped original are served to anybody
 * who can see the page, so neither may keep the GPS coordinates, timestamps, camera identity or embedded thumbnail
 * the photograph arrived with. Orientation is the exception, and only because it has to be applied to the pixels
 * rather than passed on: what you write is the image the right way up, so nothing downstream has to rotate it.
 *
 * **[ImageRefused] means never, anything else means later.** Klerk aborts the variant's job permanently on
 * [ImageRefused] — the source is not an image, or is too big to be worth decoding, and no number of retries will
 * change that. Every other exception leaves the job retryable, which is where a transformer that is down,
 * restarting or timing out belongs. Reporting a restarting sidecar as [ImageRefused] permanently marks every image
 * that happened to be in flight as broken.
 */
public interface ImageProcessor {

    /** The formats this processor can write, as bare names: `jpeg`, `png`, `webp`, `avif`. */
    public val outputFormats: Set<String>

    public val limits: ImageLimits

    /**
     * Checked once when the application starts, so that an unreachable transformer or a wrong credential is a
     * startup failure rather than images that silently never appear.
     *
     * Throw to refuse to start. The default does nothing, which is right for an implementation that has nothing to
     * be misconfigured.
     *
     * This is also where [outputFormats] should be confirmed rather than merely claimed. klerk-web checks that every
     * format a template asks for is in that set, but the set is your word: an ImageMagick without an AVIF delegate,
     * or a transformer built without one, does not grow one because the code says `avif`.
     */
    public suspend fun verify() {}

    /**
     * The size of [source] without decoding it, *as displayed*: a photograph taken sideways carries an EXIF
     * orientation, and an implementation that reports the stored width and height instead makes every page reserve
     * the wrong shape of space for it.
     *
     * Called while an image is being uploaded, where there is nothing to render yet. Generating a variant does not
     * call this — [render] reports the same thing — so a processor that has to make a roundtrip makes one.
     *
     * @return null if the file is not an image this processor understands. This is a measurement rather than a
     * check: it must not throw for an unreadable file.
     */
    public suspend fun probe(source: Path): ImageInfo?

    /**
     * Writes [source] to [target] with its metadata removed and nothing else changed.
     *
     * How is up to the implementation: rewriting the container without touching the compressed data keeps the
     * quality the uploader chose, while decoding and re-encoding is simpler and loses a little of it. What must be
     * true either way is that the result is the same image, the right way up, carrying nothing but what a browser
     * needs to display it — an ICC profile may stay, an `Artist` tag may not.
     *
     * This is what [dev.klerkframework.web.image.ImagePlugin.prepareImage] calls, once, as the file is attached, so
     * it runs against bytes a stranger chose and is worth the same suspicion as [render].
     *
     * @return the size of [source] as displayed, so that stripping and measuring are one roundtrip.
     * @throws ImageRefused if the source is too large to decode, or is not an image this processor understands.
     * Anything else means the failure was temporary and the upload is worth retrying.
     */
    public suspend fun stripMetadata(source: Path, target: Path): ImageInfo

    /**
     * Writes [source], scaled to [width] pixels wide and encoded as [format], to [target].
     *
     * An image narrower than [width] is written at its own width rather than enlarged. An image carrying an EXIF
     * orientation is turned the right way up, so [width] is the width of what the viewer sees. Transparency survives
     * into a format that has an alpha channel and is flattened onto white for one that has not.
     *
     * With a [crop], the output is exactly `served x crop.heightFor(served)`, where `served` is [width] or, for a
     * source that cannot fill it, `crop.widthOf(probe(source))`. The crop is taken after the image has been turned
     * the right way up, so a photograph shot sideways loses the edges the viewer sees at the edges.
     *
     * @return the size of [source] as displayed — what [probe] would have returned for it. Klerk needs this to lay
     * pages out, and asking for it here is what keeps generating a variant to one roundtrip.
     * @throws ImageRefused if the source is too large to decode, or is not an image.
     */
    public suspend fun render(source: Path, target: Path, width: Int, format: String, crop: Crop? = null): ImageInfo
}
