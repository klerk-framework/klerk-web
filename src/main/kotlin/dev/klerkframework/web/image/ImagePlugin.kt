package dev.klerkframework.web.image

import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkPlugin
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.datatypes.BlobPreAttachStepArgs
import dev.klerkframework.klerk.datatypes.BlobPreAttachStepResult
import dev.klerkframework.klerk.job.JobAgent
import dev.klerkframework.klerk.job.JobName
import dev.klerkframework.klerk.job.JobResult
import dev.klerkframework.klerk.job.JobStepArgs
import dev.klerkframework.klerk.job.JobType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/** How often a waiting request looks for the variant it is waiting for. */
private val pollInterval = 50.milliseconds

/**
 * One modern format and one every browser can read, narrowed to what this processor can actually produce.
 *
 * A processor that writes AVIF gets `avif, jpeg`; [ImageIoProcessor], which writes neither AVIF nor WebP, gets
 * `jpeg` alone rather than a set it would refuse.
 */
private fun defaultFormats(processor: ImageProcessor): Set<String> {
    val modern = listOf("avif", "webp").firstOrNull { processor.outputFormats.contains(it) }
    val universal = listOf("jpeg", "png").firstOrNull { processor.outputFormats.contains(it) }
    return setOfNotNull(modern, universal).ifEmpty { processor.outputFormats }
}

/** The same digest Klerk records as a value's hash, so a measurement can be keyed on bytes not yet stored. */
private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The variant-store key every image asset shares; the content hash is what tells them apart. */
internal const val ASSET: String = "asset"

/** The content types this plugin will try to make variants of. */
private val sourceContentTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif")

/**
 * Serves images in the sizes a page actually needs, instead of the size they were uploaded in.
 *
 * ```kotlin
 * val images = ImagePlugin<Ctx, Views>(
 *     variantDirectory = Path("/var/lib/myapp/variants"),
 *     processor = ImageIoProcessor(),
 * )
 * val hero = images.template("hero", widths = setOf(640, 1280, 2560), sizes = "100vw")
 *
 * val spec = SpecificationBuilder<Ctx, Views>(views).build { }.withPlugin(images)
 *
 * routing { attachedDataRoutes(support, images = images) }
 * ```
 *
 * Every image is rendered through an [ImageTemplate] — the role it plays on the page, which is what knows the widths
 * it is wanted in. A variant is requested as the last segment of an attached-data URL —
 * `/_attached/42/ab12cd/hero-640.jpeg`. The first request for one that does not exist yet schedules a job and waits
 * for it, so what comes back under a variant's URL is always that variant.
 *
 * The registered templates and [formats] are the allow-list, and are what bounds this: a request for any other size
 * is a `404`, so the number of images that can ever be generated is finite. Nothing is generated until a browser
 * actually asks for it, so a wide ladder costs nothing for an image that is always rendered small.
 *
 * Variants are stored by width and format rather than by template, so two templates that share a width share the
 * file.
 *
 * @param variantDirectory where generated images are kept. A cache: losing it costs regeneration and nothing else,
 * so it does not belong in a backup. It is node-local, so each node of a multi-node application has its own.
 * @param formats the formats a template is served in unless it declares its own. Null picks a sensible pair for
 * the processor; see [ImagePlugin.formats].
 * @param processor what does the scaling. There is deliberately no default: it decides where images an attacker
 * uploaded are decoded. [ImageIoProcessor] needs nothing installed and is the development answer.
 * @param stagingDirectory where an original is put while [processor] works on it. The default is the system
 * temporary directory, which is right for a processor running inside this JVM; point it at the volume shared with an
 * image transformer running beside the application, so that the transformer can read the file it is handed.
 * @param maxConcurrentRenders how many images may be rendered at once. This is the bounded transcode queue. Note
 * that it does not bound [prepareImageKeepExif], which runs once per upload.
 * @param renderWait how long a request for a variant that does not exist yet waits for it. Past this the request
 * is answered `503`, so it has to be generous enough that a slow answer means an overloaded application rather
 * than merely a cold one: a page of cold images serialises through [maxConcurrentRenders].
 * @param retryAfter how long a scheduled-but-not-yet-produced variant is left alone before another request may
 * schedule it again.
 * @param sweepExpression how often the directory is reconciled against Klerk, as a cron expression.
 * @param maxVariantBytes how much [variantDirectory] may hold before the sweep starts evicting whole images,
 * oldest first. Null, the default, is unbounded: klerk-web cannot know how much disk it has been given. An evicted
 * image costs one render the next time it is asked for, and that render is one a request waits for, so choose a
 * budget that is not reached often.
 */
public class ImagePlugin<C : KlerkContext, V>(
    variantDirectory: Path,
    formats: Set<String>? = null,
    public val processor: ImageProcessor,
    private val stagingDirectory: Path? = null,
    private val maxConcurrentRenders: Int = 2,
    internal val renderWait: Duration = 5.seconds,
    private val retryAfter: Duration = 5.minutes,
    private val sweepExpression: String = "0 * * * *",
    private val maxVariantBytes: Long? = null,
) : KlerkPlugin<C, V> {

    override val name: String = "Images"

    /**
     * The formats a template is served in unless it declares its own.
     *
     * Left unset, it is the best modern format [processor] can write alongside one every browser can read — `avif,
     * jpeg` for a transformer that can manage it, plain `jpeg` for [ImageIoProcessor], which cannot. Naming a set
     * yourself overrides that, and is checked against what the processor can actually write.
     */
    public val formats: Set<String> = formats ?: defaultFormats(processor)

    override val description: String =
        """Serves attached images in declared sizes. A size that has not been generated yet is produced by a job,
            |which the request waits for.""".trimMargin()

    init {
        val unsupported = this.formats.minus(processor.outputFormats)
        require(unsupported.isEmpty()) {
            "${processor::class.simpleName} cannot write $unsupported, only ${processor.outputFormats}"
        }
        require(this.formats.isNotEmpty()) { "An image plugin with no formats has nothing to serve" }
    }

    internal val store: VariantStore = VariantStore(variantDirectory)

    /** Every rendition that may be asked for, by the name that identifies it in a URL. */
    private val renditions = ConcurrentHashMap<String, ImageRendition>()

    /**
     * Declares a role an image plays on a page, and what follows from it. See [ImageTemplate].
     *
     * ```kotlin
     * val hero = images.template("hero", widths = setOf(640, 1280, 2560), sizes = "100vw",
     *                            loading = ImageLoading.Eager, fetchPriority = FetchPriority.High)
     * val avatar = images.template("avatar", widths = setOf(48, 96, 192), sizes = "48px", crop = Crop(1, 1))
     * ```
     *
     * The trailing block declares alternatives for viewports the default does not suit — art direction, where the
     * phone gets a different crop rather than a smaller copy of the same one:
     *
     * ```kotlin
     * val hero = images.template("hero", widths = setOf(640, 1280, 2560), sizes = "100vw", crop = Crop(16, 9)) {
     *     on("mobile", media = "(max-width: 600px)", widths = setOf(320, 640), sizes = "100vw", crop = Crop(4, 5))
     * }
     * ```
     *
     * Registering is what lets the route serve the template, so keep the result: an image can only be rendered
     * through the template it was declared for.
     */
    public fun template(
        name: String,
        widths: Set<Int>,
        sizes: String? = null,
        crop: Crop? = null,
        formats: Set<String>? = null,
        loading: ImageLoading = ImageLoading.Lazy,
        fetchPriority: FetchPriority? = null,
        alternatives: (ImageTemplate.Builder.() -> Unit)? = null,
    ): ImageTemplate<C, V> {
        // Letting the browser measure the image is the right default, but it only measures a lazily loaded one,
        // so an eager template gets the width it would have fallen back to anyway.
        val measured = sizes ?: if (loading == ImageLoading.Eager) "100vw" else "auto, 100vw"
        val served = formats ?: this.formats
        require(served.isNotEmpty()) { "The template '$name' has no formats, so it has nothing to serve" }
        val unsupported = served.minus(processor.outputFormats)
        require(unsupported.isEmpty()) {
            "The template '$name' asks for $unsupported, which ${processor::class.simpleName} cannot write"
        }
        val builder = ImageTemplate.Builder(name, served).apply { alternatives?.invoke(this) }
        val default =
            ImageRendition(name, media = null, widths = widths, sizes = measured, crop = crop, formats = served)
        val template = ImageTemplate(default, builder.alternatives.toList(), loading, fetchPriority, this)
        (listOf(default) + template.alternatives).forEach { rendition ->
            require(renditions.putIfAbsent(rendition.name, rendition) == null) {
                "There is already an image template or alternative called '${rendition.name}'"
            }
        }
        return template
    }

    /**
     * Static images registered by the assets plugin, by content hash. They ship with the application, so the set is
     * known at startup and never changes while it runs - which is why an asset always has its dimensions and never
     * renders without `width`/`height`.
     */
    private val assets = ConcurrentHashMap<String, Path>()

    /**
     * Declares a static image, so that variants of it can be rendered like any other.
     *
     * $param hash the asset's content hash, which is its identity: two assets cannot collide, and a redeploy that
     * changes the file leaves the old variants to be swept.
     * $param source the file to render from. It has to outlive the application, so extract a resource to
     * `stagingDirectory` rather than pointing at something inside a jar.
     * $throws IllegalStateException if the image cannot be read or is bigger than [ImageProcessor.limits] allows.
     * An asset is written by whoever wrote the application, so a bad one is a mistake worth failing the boot for.
     */
    internal suspend fun registerAsset(hash: String, source: Path, name: String) {
        val info = try {
            processor.probe(source)
        } catch (e: Exception) {
            throw IllegalStateException("The image asset '$name' could not be read", e)
        } ?: throw IllegalStateException("The image asset '$name' is not an image ${processor::class.simpleName} can read")
        check(info.pixels <= processor.limits.maxPixels) {
            "The image asset '$name' is ${info.width}x${info.height} (${info.pixels} pixels), and at most " +
                    "${processor.limits.maxPixels} are allowed"
        }
        assets[hash] = source
        store.writeSidecar(ASSET, hash, ImageSidecar(info.width, info.height))
    }

    /** Keys currently being generated, so that N simultaneous misses schedule one job rather than N. */
    private val inFlight = ConcurrentHashMap<String, Instant>()

    /**
     * Variants the processor has refused, so that a request for one is answered at once instead of waiting
     * [renderWait] for a job that will never succeed. An image nothing can render would otherwise cost a held
     * connection on every request, for as long as it exists.
     */
    private val refused: MutableSet<String> =
        Collections.newSetFromMap(Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > 1000
        }))

    /**
     * What [prepareImageKeepExif] learned, keyed by the file's hash because the attached-data id is not known to a
     * pre-attach step. Read once and written through to the variant directory, so this only has to survive from the
     * upload to the first page that renders the image.
     */
    private val described: MutableMap<String, ImageSidecar> =
        Collections.synchronizedMap(object : LinkedHashMap<String, ImageSidecar>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageSidecar>): Boolean = size > 1000
        })

    private lateinit var klerk: Klerk<C, V>

    /**
     * What this plugin publishes to [dev.klerkframework.klerk.KlerkSettings.meterRegistry].
     *
     * A request waits for the variant it asked for, so the render queue is on the critical path of a page load:
     * [wait] and [unavailable] together are what say whether [renderWait] and [maxConcurrentRenders] are set for the
     * load this application actually has.
     */
    private class Meters(registry: MeterRegistry, store: VariantStore) {
        /** How long one variant takes to produce. */
        val render: Timer = registry.timer("klerk.web.image.render")

        /** Images the processor would not decode. Each is answered `404` from then on. */
        val refused: Counter = registry.counter("klerk.web.image.refused")

        /** How long a request waited for a variant that did not exist yet. */
        val wait: Timer = registry.timer("klerk.web.image.wait")

        /** Requests answered `503` because the wait ran out. */
        val unavailable: Counter = registry.counter("klerk.web.image.unavailable")

        init {
            registry.gauge("klerk.web.image.bytes", store) { it.totalBytes().toDouble() }
        }
    }

    private var meters: Meters? = null

    private val generateJob = GenerateVariant()
    private val sweepJob = SweepVariants()

    override fun mergeSpecification(previous: Specification<C, V>): Specification<C, V> =
        previous.withJobs {
            register(generateJob)
            register(sweepJob)
            cron(sweepJob, sweepExpression) { cursor = "" }
        }

    override suspend fun start(klerk: Klerk<C, V>) {
        this.klerk = klerk
        meters = Meters(klerk.settings.meterRegistry, store)
        // Before anything else: an unreachable or misconfigured processor should stop the application here, rather
        // than turn into images that silently never appear.
        processor.verify()
        sweep()
    }

    /** Counts a request that gave up waiting and was answered `503`. */
    internal fun recordUnavailable() {
        meters?.unavailable?.increment()
    }

    /** Whether [contentType] is something this plugin will scale. */
    internal fun handles(contentType: String?): Boolean = contentType != null && sourceContentTypes.contains(contentType)

    /**
     * Whether a URL's last segment names a registered template or alternative at all, as opposed to naming a
     * download. `hero-640.jpeg` and `hero-mobile-320.jpeg` are; `report.pdf` and `holiday-2024.jpeg` are not.
     */
    internal fun looksLikeVariant(segment: String): Boolean = split(segment) != null

    /**
     * The variant [segment] asks for, or null when it asks for a width or format the rendition does not offer —
     * which is a `404` rather than a download, since [looksLikeVariant] has already said this is a variant request.
     */
    internal fun parseVariant(segment: String): RequestedVariant? {
        val asked = split(segment) ?: return null
        val allowed = asked.rendition.widths.contains(asked.width) && asked.rendition.formats.contains(asked.format)
        return if (allowed) RequestedVariant(asked.width, asked.format, asked.rendition.crop) else null
    }

    private fun split(segment: String): Asked? {
        val dot = segment.lastIndexOf('.')
        if (dot <= 0) {
            return null
        }
        val format = formatForExtension(segment.substring(dot + 1)) ?: return null
        val name = segment.substring(0, dot)
        val dash = name.lastIndexOf('-')
        if (dash <= 0) {
            return null
        }
        val width = name.substring(dash + 1).toIntOrNull() ?: return null
        // The name may hold dashes of its own - 'hero-mobile' - so everything before the last one is the rendition.
        val rendition = renditions[name.substring(0, dash)] ?: return null
        return Asked(rendition, width, format)
    }

    private class Asked(val rendition: ImageRendition, val width: Int, val format: String)

    /** The file holding this variant, or null if it has not been generated. */
    internal fun existing(id: String, hash: String, variant: RequestedVariant): Path? =
        store.variant(id, hash, variant.width, variant.format, variant.crop)

    /** Whether this variant has already been refused, and is therefore never going to exist. */
    internal fun refused(id: String, hash: String, variant: RequestedVariant): Boolean =
        refused.contains(variant.key(id, hash))

    /**
     * Waits for the variant to be generated, for at most [renderWait].
     *
     * Polls the file rather than the job: a caller can arrive before the job was scheduled, after it has finished,
     * or after a restart carried it away, and a file that either exists or does not has none of those cases.
     *
     * @return the file, or null if it was refused or is still not there.
     */
    internal suspend fun awaitVariant(id: String, hash: String, variant: RequestedVariant): Path? {
        val key = variant.key(id, hash)
        val started = Clock.System.now()
        val deadline = started.plus(renderWait)
        try {
            while (true) {
                existing(id, hash, variant)?.let { return it }
                if (refused.contains(key) || Clock.System.now() >= deadline) {
                    return null
                }
                delay(pollInterval)
            }
        } finally {
            meters?.wait?.record((Clock.System.now() - started).inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * What is known about an image: its real size.
     *
     * Comes from the variant directory once anything has been generated, and before that from what
     * [prepareImageKeepExif] worked out while the file was being attached — written through to disk here, where the id is
     * finally known. Null when neither has happened, in which case a page renders without `width`/`height`.
     */
    public fun sidecar(id: AttachedDataID, hash: String): ImageSidecar? = sidecar("${id.value}", hash)

    /** As [sidecar], for any image this store holds - an attached one by its id, an asset by [ASSET]. */
    internal fun sidecar(id: String, hash: String): ImageSidecar? =
        store.sidecar(id, hash) ?: described[hash]?.also { store.writeSidecar(id, hash, it) }

    /**
     * An optional [dev.klerkframework.klerk.datatypes.BlobPreAttachStep] that measures the image as it is attached.
     *
     * The size is what lets [image][dev.klerkframework.web.image.image] render the `width` and `height` attributes,
     * which a browser needs to reserve space for an image whose height follows its own proportions — one laid out
     * with `height: auto`. A slot whose box the stylesheet pins, by both dimensions or by `aspect-ratio`, does not
     * need them and does not need this step.
     *
     * The first generated variant records the size anyway. Declaring this only moves it earlier, to cover the window
     * between the upload and that first variant — which is the page whoever uploaded is looking at.
     *
     * It also refuses an image bigger than [ImageProcessor.limits] allows, which is the only point at which that cap
     * can be applied before the bytes are stored rather than every time somebody asks for a variant of them.
     *
     * **Declare it last**, after anything that rewrites the bytes:
     *
     * ```kotlin
     * override val preAttachSteps = listOf(::scanForViruses, images::prepareImageKeepExif)
     * ```
     *
     * It keys what it learns on the file's hash, and a later step that rewrites the bytes changes that hash — the
     * measurement is then never found again, and pages fall back to rendering without dimensions.
     *
     * An image it cannot measure is passed rather than rejected: a processor that is unreachable would otherwise
     * refuse every upload for as long as it is down. Those are refused when a variant of them is asked for instead.
     *
     * **This keeps whatever the photograph arrived with** — where it was taken, when, on what, and often a thumbnail
     * of an earlier crop of it. Use [prepareImage] unless something else in the application needs that.
     */
    public suspend fun prepareImageKeepExif(args: BlobPreAttachStepArgs): BlobPreAttachStepResult {
        if (!handles(args.metadata.contentType)) {
            return BlobPreAttachStepResult.Pass
        }
        val file = withContext(Dispatchers.IO) { newStagingFile("klerk-probe-") }
        val info = try {
            withContext(Dispatchers.IO) { Files.copy(args.value, file, StandardCopyOption.REPLACE_EXISTING) }
            processor.probe(file)
        } catch (e: Exception) {
            log.debug(e) { "Could not measure the image" }
            null
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(file) }
        }
        if (info == null) {
            return BlobPreAttachStepResult.Pass
        }
        if (info.pixels > processor.limits.maxPixels) {
            return BlobPreAttachStepResult.Reject(
                "The image is ${info.width}x${info.height} (${info.pixels} pixels), and at most " +
                        "${processor.limits.maxPixels} are allowed"
            )
        }
        described[args.metadata.hash] = ImageSidecar(info.width, info.height)
        return BlobPreAttachStepResult.Pass
    }

    /**
     * [prepareImageKeepExif], and the stored image carries no metadata.
     *
     * A photograph arrives knowing where it was taken, when, on what, who owns the camera, and often holding a
     * thumbnail of an earlier crop of itself. None of that is what somebody uploading a profile picture meant to
     * publish, and the original is the one thing an application is most likely to hand out unchanged. This replaces
     * the bytes with the same image, the right way up, holding nothing but what a browser needs to display it.
     *
     * ```kotlin
     * class Portrait(id: AttachedBlobID) : AttachedBlobContainer(id) {
     *     override val accept = setOf("image/jpeg", "image/png")
     *     override val preAttachSteps = listOf(::preparePortrait)
     * }
     *
     * suspend fun preparePortrait(args: BlobPreAttachStepArgs) = images.prepareImage(args)
     * ```
     *
     * [ImageProcessor.stripMetadata] decides how, so what it costs is the processor's choice: rewriting the
     * container keeps the uploader's quality, decoding and re-encoding loses a little of it.
     *
     * Unlike [prepareImageKeepExif] this **refuses what it cannot sanitise**, because passing the file on would mean
     * promising something it had not done. An image the processor will not decode is rejected with the reason; a
     * processor that is merely unreachable makes the step fail rather than reject, so Klerk retries the upload with
     * the usual backoff instead of blaming the person who sent it.
     */
    public suspend fun prepareImage(args: BlobPreAttachStepArgs): BlobPreAttachStepResult {
        if (!handles(args.metadata.contentType)) {
            return BlobPreAttachStepResult.Pass
        }
        val source = withContext(Dispatchers.IO) { newStagingFile("klerk-prepare-") }
        val stripped = withContext(Dispatchers.IO) { newStagingFile("klerk-stripped-") }
        try {
            withContext(Dispatchers.IO) { Files.copy(args.value, source, StandardCopyOption.REPLACE_EXISTING) }
            val info = try {
                processor.stripMetadata(source, stripped)
            } catch (e: ImageRefused) {
                return BlobPreAttachStepResult.Reject("The image was refused: ${e.message}")
            }
            if (info.pixels > processor.limits.maxPixels) {
                return BlobPreAttachStepResult.Reject(
                    "The image is ${info.width}x${info.height} (${info.pixels} pixels), and at most " +
                            "${processor.limits.maxPixels} are allowed"
                )
            }
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stripped) }
            // What was measured describes the bytes about to be stored, not the ones that arrived, so it is keyed on
            // the hash those bytes will have. Klerk hashes the replacement the same way.
            described[sha256(bytes)] = ImageSidecar(info.width, info.height)
            return BlobPreAttachStepResult.Replace(bytes.inputStream())
        } finally {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(source)
                Files.deleteIfExists(stripped)
            }
        }
    }

    /**
     * Makes sure the variant is being produced, without producing it here.
     *
     * Scheduling a job is a command on the single writer, so this is single-flighted: the first miss schedules, the
     * rest do nothing. A refusal — an overloaded job queue, say — leaves the fallback serving and lets a later
     * request try again once [retryAfter] has passed.
     */
    internal suspend fun requestVariant(id: String, hash: String, variant: RequestedVariant) {
        val key = variant.key(id, hash)
        val now = Clock.System.now()
        val claimed = inFlight.compute(key) { _, existing ->
            if (existing != null && now < existing.plus(retryAfter)) existing else now
        }
        if (claimed != now) {
            return
        }
        // Look again now that this call owns the key. A request that read "missing" just before the job published the
        // file, and got here just after the job released the key, would otherwise schedule the same work twice.
        if (existing(id, hash, variant) != null) {
            inFlight.remove(key, now)
            return
        }
        try {
            klerk.jobs.schedule(
                generateJob.declare("$id|$hash|${variant.width}|${variant.format}|${variant.crop.encodedOrNone()}"),
                systemContext(),
            )
        } catch (e: Exception) {
            // The job queue can refuse work when it is not draining. Nothing is broken: the fallback is being served,
            // and the next request after the cooldown will try again.
            log.debug(e) { "Could not schedule the generation of $key" }
            inFlight.remove(key, now)
        }
    }

    private fun systemContext(): C = klerk.spec.systemContextProvider(SystemIdentity)

    /** Deletes what belongs to attached data that is gone, or whose content has been replaced. */
    internal suspend fun sweep() {
        val context = systemContext()
        store.all().forEach { (id, hash) ->
            if (id == ASSET) {
                // A redeploy that changes an image changes its hash, so the variants of the old one are garbage.
                if (!assets.containsKey(hash)) {
                    log.info { "Deleting the variants of the image asset $hash, which is no longer registered" }
                    store.delete(id, hash)
                }
                return@forEach
            }
            val numeric = id.toIntOrNull()
            val metadata = if (numeric == null) null else try {
                klerk.attachedData.getMetadata(AttachedDataID(numeric), context)
            } catch (e: NoSuchElementException) {
                null
            }
            if (metadata == null || metadata.hash != hash) {
                log.info { "Deleting the variants of attached data $id, which no longer has the hash $hash" }
                store.delete(id, hash)
            }
        }
        // Only here, never while serving: an eviction costs the next request for that image a render it has to wait
        // for, so it happens on the schedule the application chose.
        maxVariantBytes?.let { store.evictTo(it) }
    }

    internal data class RequestedVariant(val width: Int, val format: String, val crop: Crop?) {
        /** What to serve this format as. */
        val contentType: String get() = "image/$format"

        /** What one variant of one image is called while it is being made. */
        fun key(id: String, hash: String): String = "$id-$hash-$width-${crop.encodedOrNone()}.$format"
    }

    /**
     * Produces one variant, plus the sidecar if it is not there yet.
     *
     * `Local` because it writes machine-local files. The cursor is `id|hash|width|format|crop`, holding the crop
     * itself rather than the rendition that asked for it, so that a job outliving a redeploy still makes the right
     * file.
     */
    private inner class GenerateVariant : JobType.Local<String, C, V>() {
        override val name: JobName = JobName("klerk-web-image-variant")
        override val agent: JobAgent = JobAgent.System
        override val maxConcurrent: Int = maxConcurrentRenders

        override suspend fun step(args: JobStepArgs.Local<String, C, V>): JobResult<String> {
            val parsed = parse(args.cursor) ?: return JobResult.Abort(
                "'${args.cursor}' is not id|hash|width|format|crop",
                runHook = false,
            )
            val (id, hash, width, format) = parsed
            val crop = parsed.crop
            val key = RequestedVariant(width, format, crop).key(id, hash)
            try {
                if (store.variant(id, hash, width, format, crop) != null) {
                    return JobResult.Success(log = listOf(args.info("$key already exists")))
                }
                if (id != ASSET) {
                    val numeric = id.toIntOrNull()
                        ?: return JobResult.Abort("'$id' is neither an attached-data id nor an asset", runHook = false)
                    val metadata = try {
                        args.klerk.attachedData.getMetadata(AttachedDataID(numeric), args.context)
                    } catch (e: NoSuchElementException) {
                        return JobResult.Abort("the attached data $id is gone", runHook = false)
                    }
                    if (metadata.hash != hash) {
                        return JobResult.Abort("the attached data $id no longer has the hash $hash", runHook = false)
                    }
                } else if (!assets.containsKey(hash)) {
                    return JobResult.Abort("there is no image asset with the hash $hash", runHook = false)
                }

                val original = stage(args.klerk, id, hash, args.context)
                try {
                    val partial = store.temporaryFor(id, hash)
                    try {
                        // The render reports the size of the original too, so a processor that has to leave this JVM
                        // makes one roundtrip per variant rather than a probe and then a render.
                        val startedRender = Clock.System.now()
                        val info = processor.render(original, partial, width, format, crop)
                        meters?.render?.record(
                            (Clock.System.now() - startedRender).inWholeMilliseconds,
                            TimeUnit.MILLISECONDS,
                        )
                        if (store.sidecar(id, hash) == null) {
                            store.writeSidecar(id, hash, ImageSidecar(info.width, info.height))
                        }
                        store.publish(partial, id, hash, width, format, crop)
                    } finally {
                        Files.deleteIfExists(partial)
                    }
                } finally {
                    Files.deleteIfExists(original)
                }
                return JobResult.Success(log = listOf(args.info("Generated $key")))
            } catch (e: ImageRefused) {
                // Never going to work: remember it, so requests are answered instead of waiting for it.
                refused.add(key)
                meters?.refused?.increment()
                return JobResult.Abort("the image was refused: ${e.message}", runHook = false)
            } finally {
                inFlight.remove(key)
            }
        }

        private fun parse(cursor: String): Parsed? {
            val parts = cursor.split("|")
            if (parts.size != 5) {
                return null
            }
            val width = parts[2].toIntOrNull() ?: return null
            val crop = if (parts[4] == NO_CROP) null else parseCrop(parts[4]) ?: return null
            return Parsed(parts[0], parts[1], width, parts[3], crop)
        }
    }

    private data class Parsed(
        val id: String,
        val hash: String,
        val width: Int,
        val format: String,
        val crop: Crop?,
    )

    /** An empty file in [stagingDirectory], which is where a processor outside this JVM is able to read it. */
    private fun newStagingFile(prefix: String): Path =
        if (stagingDirectory == null) {
            Files.createTempFile(prefix, ".original")
        } else {
            Files.createDirectories(stagingDirectory)
            Files.createTempFile(stagingDirectory, prefix, ".original")
        }

    /**
     * A file the processor can seek around in: the original copied out of Klerk, or the asset already extracted.
     *
     * An asset's own file is returned as it is, so [GenerateVariant] must not delete what it is given - hence the
     * copy for that case too.
     */
    private suspend fun stage(klerk: Klerk<C, V>, id: String, hash: String, context: C): Path {
        val file = newStagingFile("klerk-image-")
        val source = if (id == ASSET) {
            Files.newInputStream(checkNotNull(assets[hash]) { "no asset with the hash $hash" })
        } else {
            klerk.attachedData.get(AttachedDataID(id.toInt()).asBlob(), context)
        }
        source.use { input ->
            withContext(Dispatchers.IO) {
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return file
    }

    private inner class SweepVariants : JobType.Local<String, C, V>() {
        override val name: JobName = JobName("klerk-web-image-sweep")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<String, C, V>): JobResult<String> {
            sweep()
            return JobResult.Success(log = listOf(args.info("Swept the image variant directory")))
        }
    }
}

private fun formatForExtension(extension: String): String? = when (extension.lowercase()) {
    "jpg", "jpeg" -> "jpeg"
    "png" -> "png"
    "webp" -> "webp"
    "avif" -> "avif"
    else -> null
}
