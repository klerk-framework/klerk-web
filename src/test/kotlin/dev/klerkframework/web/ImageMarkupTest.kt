package dev.klerkframework.web

import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedDataKind
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.web.config.*
import dev.klerkframework.web.image.Crop
import dev.klerkframework.web.image.FetchPriority
import dev.klerkframework.klerk.BlobRejected
import dev.klerkframework.web.image.ImageIoProcessor
import dev.klerkframework.web.image.ImageLimits
import dev.klerkframework.web.image.ImageProcessor
import dev.klerkframework.web.image.ImageLoading
import dev.klerkframework.web.image.ImagePlugin
import dev.klerkframework.web.image.ImageSidecar
import dev.klerkframework.web.image.ImageTemplate
import dev.klerkframework.web.image.image
import kotlinx.coroutines.runBlocking
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HTML. What matters is that the browser is told enough to pick the right file and to reserve the right space
 * before any of it has arrived.
 */
class ImageMarkupTest {

    private val actor = Context(CustomIdentity(id = null, externalId = 1))

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = Color(200, 60, 40)
            fillRect(0, 0, width, height)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private class Setup(
        val klerk: Klerk<Context, MyCollections>,
        val images: ImagePlugin<Context, MyCollections>,
        val gallery: ImageTemplate<Context, MyCollections>,
    )

    private fun setup(
        formats: Set<String> = setOf("png"),
        sizes: String = "100vw",
        processor: ImageProcessor = ImageIoProcessor(),
    ): Setup {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val images = ImagePlugin<Context, MyCollections>(
            Files.createTempDirectory("klerk-markup"),
            formats = formats,
            processor = processor,
        )
        val gallery = images.template("gallery", widths = setOf(320, 640), sizes = sizes)
        val klerk = Klerk.create(createConfig(collections).withPlugin(images), testSettings())
        return Setup(klerk, images, gallery)
    }

    private fun Setup.render(metadata: AttachedDataMetadata): String {
        val support = WebSupport(klerk, { _, _ -> actor })
        return createHTML().div { with(support) { image(gallery, metadata, alt = "A photo") } }
    }

    /**
     * `image` is the obvious name for the reference at a call site, so calling the function from a scope where a
     * local of that name exists has to keep working.
     */
    @Test
    fun `a local called image does not shadow the function`() {
        val setup = setup()
        val support = WebSupport(setup.klerk, { _, _ -> actor })
        val image = setup.described(ImageSidecar(4000, 3000))

        val html = createHTML().div { with(support) { image(setup.gallery, image, alt = "A photo") } }

        assertTrue(html.contains("srcset="), html)
    }

    /**
     * Metadata for an image this plugin has already measured — what a page renders from. The dimensions live in the
     * plugin rather than in the metadata, so a page that renders before anything measured the image gets no
     * width/height, which [sidecar] being null stands for.
     */
    private fun Setup.described(sidecar: ImageSidecar?): AttachedDataMetadata {
        sidecar?.let { images.store.writeSidecar("42", "ab12cd", it) }
        return AttachedDataMetadata(
            id = AttachedDataID(42),
            kind = AttachedDataKind.Blob,
            visibility = AttachedDataVisibility.Public,
            createdAt = Instant.fromEpochSeconds(0),
            size = 1000,
            hash = "ab12cd",
            custom = emptyMap(),
            contentType = "image/png",
        )
    }

    @Test
    fun `a described image gets a srcset and the dimensions that stop the page jumping`() {
        val setup = setup(sizes = "50vw")

        val html = setup.render(setup.described(ImageSidecar(4000, 3000)))

        assertTrue(
            html.contains("""srcset="/_attached/42/ab12cd/gallery-320.png 320w, /_attached/42/ab12cd/gallery-640.png 640w""""),
            html,
        )
        assertTrue(html.contains("""src="/_attached/42/ab12cd/gallery-640.png""""), html)
        assertTrue(html.contains("""sizes="50vw""""), html)
        assertTrue(html.contains("""width="640""""), html)
        assertTrue(html.contains("""height="480""""), html)   // 4000x3000 at 640 wide
        assertTrue(html.contains("""loading="lazy""""), html)
        assertTrue(html.contains("""alt="A photo""""), html)
    }

    @Test
    fun `an image nobody has described yet still renders, without dimensions`() {
        val setup = setup()

        val html = setup.render(setup.described(null))

        assertTrue(html.contains("srcset="), html)
        assertFalse(html.contains("width="), html)
        assertFalse(html.contains("height="), html)
    }

    @Test
    fun `a descriptor never promises more pixels than the file has`() {
        val setup = setup()

        val html = setup.render(setup.described(ImageSidecar(400, 300)))

        assertTrue(html.contains("320w"), html)
        assertFalse(html.contains("640w"), html)
        assertTrue(html.contains("""width="320""""), html)
    }

    @Test
    fun `an image smaller than every width of the template is described by its real width`() {
        val setup = setup()

        val html = setup.render(setup.described(ImageSidecar(100, 50)))

        // the only variant that can be served is gallery-320, and it will be 100 px wide - so say 100w
        assertTrue(html.contains("""/_attached/42/ab12cd/gallery-320.png 100w"""), html)
        assertTrue(html.contains("""width="100""""), html)
        assertTrue(html.contains("""height="50""""), html)
    }

    @Test
    fun `a template that does not say how wide it is rendered lets the browser measure it`() {
        val setup = setup()
        val bare = setup.images.template("bare", widths = setOf(320, 640))
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(bare, setup.described(ImageSidecar(4000, 3000)), alt = "A photo") } }

        assertTrue(html.contains("""sizes="auto, 100vw""""), html)
        assertTrue(html.contains("""loading="lazy""""), html)
    }

    @Test
    fun `an eager template defaults to a width instead of auto, which it would never be measured for`() {
        val setup = setup()
        val eager = setup.images.template("eagerbare", widths = setOf(320, 640), loading = ImageLoading.Eager)
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(eager, setup.described(ImageSidecar(4000, 3000)), alt = "A photo") } }

        assertTrue(html.contains("""sizes="100vw""""), html)
        assertFalse(html.contains("auto"), html)
    }

    @Test
    fun `asking for auto on an eager template is still refused`() {
        val setup = setup()

        val thrown = assertFailsWith<IllegalArgumentException> {
            setup.images.template("contradiction", widths = setOf(320), sizes = "auto, 320px", loading = ImageLoading.Eager)
        }

        assertTrue(thrown.message!!.contains("lazily loaded"), thrown.message)
    }

    @Test
    fun `prepareImageKeepExif refuses an image with more pixels than the processor will decode`() = runBlocking {
        val setup = setup(processor = ImageIoProcessor(ImageLimits(maxPixels = 1000)))
        testImagePlugin = setup.images
        try {
            setup.klerk.meta.start(installShutdownHook = false)
            val blob = setup.klerk.attachedData.prepare(png(800, 400).inputStream(), DescribedImage::class, actor)

            val thrown = assertFailsWith<BlobRejected> { setup.klerk.attachedData.awaitProcessing(blob) }

            assertTrue(thrown.message!!.contains("320000"), thrown.message)
            assertTrue(thrown.message!!.contains("1000"), thrown.message)
            setup.klerk.meta.stop()
        } finally {
            testImagePlugin = null
        }
    }

    @Test
    fun `prepareImageKeepExif accepts an image within the cap, and one it cannot read at all`() = runBlocking {
        val setup = setup(processor = ImageIoProcessor(ImageLimits(maxPixels = 1_000_000)))
        testImagePlugin = setup.images
        try {
            setup.klerk.meta.start(installShutdownHook = false)

            val fine = setup.klerk.attachedData.prepare(png(800, 400).inputStream(), DescribedImage::class, actor)
            setup.klerk.attachedData.awaitProcessing(fine)

            // Not an image the processor understands. Measuring is best-effort, so it is stored rather than refused;
            // a variant of it is what gets refused, later.
            val gibberish = setup.klerk.attachedData.prepare(
                ByteArray(64) { 9 }.inputStream(),
                DescribedImage::class,
                actor,
            )
            setup.klerk.attachedData.awaitProcessing(gibberish)
            setup.klerk.meta.stop()
        } finally {
            testImagePlugin = null
        }
    }
    @Test
    fun `a cropped template reserves space before anything has measured the image`() {
        val setup = setup()
        val square = setup.images.template("unmeasured", widths = setOf(200), sizes = "200px", crop = Crop(1, 1))
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        // described(null) writes no sidecar, so nothing knows the image's own proportions - but a crop is a ratio
        // the template declared, so the box is known anyway.
        val html = createHTML().div { with(support) { image(square, setup.described(null), alt = "A photo") } }

        assertTrue(html.contains("""width="200""""), html)
        assertTrue(html.contains("""height="200""""), html)
    }

    @Test
    fun `an uncropped template still waits to be measured`() {
        val setup = setup()

        val html = setup.render(setup.described(null))

        assertFalse(html.contains("width="), html)
        assertFalse(html.contains("height="), html)
    }
    @Test
    fun `a template locked to one format is served in it, whatever the plugin offers`() {
        val setup = setup(formats = setOf("png", "jpeg"))
        val logo = setup.images.template("logo", widths = setOf(120, 240), sizes = "120px", formats = setOf("png"))
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(logo, setup.described(ImageSidecar(480, 240)), alt = "A logo") } }

        // One format, so a plain <img> - and nothing offers the jpeg the plugin would otherwise have added.
        assertFalse(html.contains("<picture"), html)
        assertFalse(html.contains("jpeg"), html)
        assertTrue(html.contains("/_attached/42/ab12cd/logo-240.png"), html)
    }

    @Test
    fun `a template may ask for a format the plugin does not list`() {
        val setup = setup(formats = setOf("jpeg"))
        val logo = setup.images.template("logo", widths = setOf(120), sizes = "120px", formats = setOf("png"))
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(logo, setup.described(ImageSidecar(480, 240)), alt = "A logo") } }

        assertTrue(html.contains("/_attached/42/ab12cd/logo-120.png"), html)
    }

    @Test
    fun `a template cannot ask for a format the processor cannot write`() {
        val setup = setup()

        val thrown = assertFailsWith<IllegalArgumentException> {
            setup.images.template("fancy", widths = setOf(120), sizes = "120px", formats = setOf("avif"))
        }

        assertTrue(thrown.message!!.contains("fancy"), thrown.message)
        assertTrue(thrown.message!!.contains("avif"), thrown.message)
    }

    @Test
    fun `several formats become a picture, one does not`() {
        val single = setup(formats = setOf("png")).run { render(described(ImageSidecar(4000, 3000))) }
        assertFalse(single.contains("<picture"), single)

        val several = setup(formats = setOf("png", "jpeg")).run { render(described(ImageSidecar(4000, 3000))) }
        assertTrue(several.contains("<picture"), several)
        assertTrue(several.contains("""type="image/jpeg""""), several)
        // the fallback <img> is the last preference, and the browser picks the rest
        assertTrue(several.contains("""src="/_attached/42/ab12cd/gallery-640.png""""), several)
    }

    @Test
    fun `a template carries the loading and priority its role needs`() {
        val setup = setup()
        val hero = setup.images.template(
            "hero",
            widths = setOf(640),
            sizes = "100vw",
            loading = ImageLoading.Eager,
            fetchPriority = FetchPriority.High,
        )
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(hero, setup.described(ImageSidecar(4000, 3000)), alt = "A photo") } }

        assertTrue(html.contains("""loading="eager""""), html)
        assertTrue(html.contains("""fetchpriority="high""""), html)
        assertTrue(html.contains("/_attached/42/ab12cd/hero-640.png"), html)
    }

    @Test
    fun `sizes auto is rendered as it stands on a lazy image`() {
        val setup = setup()
        val auto = setup.images.template("auto", widths = setOf(320, 640), sizes = "auto, 320px")
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(auto, setup.described(ImageSidecar(4000, 3000)), alt = "A photo") } }

        assertTrue(html.contains("""sizes="auto, 320px""""), html)
        assertTrue(html.contains("""loading="lazy""""), html)
    }

    @Test
    fun `an eager image gets the fallback instead of auto, which it would not be measured for`() {
        val setup = setup()
        val deferred = setup.images.template("deferred", widths = setOf(320, 640), sizes = "auto, 320px")
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div {
            with(support) {
                image(deferred, setup.described(ImageSidecar(4000, 3000)), alt = "A photo", loading = ImageLoading.Eager)
            }
        }

        assertTrue(html.contains("""sizes="320px""""), html)
        assertFalse(html.contains("auto"), html)
    }

    @Test
    fun `sizes auto is refused where a browser would ignore it`() {
        val setup = setup()

        // eager: the browser never measures it
        assertFailsWith<IllegalArgumentException> {
            setup.images.template("eager-auto", widths = setOf(320), sizes = "auto, 320px", loading = ImageLoading.Eager)
        }
        // no fallback: a browser that does not know 'auto' would use 100vw
        assertFailsWith<IllegalArgumentException> {
            setup.images.template("bare-auto", widths = setOf(320), sizes = "auto")
        }
    }

    @Test
    fun `an alternative is offered before the default, with its own shape`() {
        val setup = setup()
        val hero = setup.images.template(
            "hero",
            widths = setOf(640, 1280),
            sizes = "100vw",
            crop = Crop(16, 9),
        ) {
            on("mobile", media = "(max-width: 600px)", widths = setOf(320), sizes = "100vw", crop = Crop(4, 5))
        }
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(hero, setup.described(ImageSidecar(4000, 3000)), alt = "A photo") } }

        val mobile = html.indexOf("hero-mobile-320.png")
        val desktop = html.indexOf("hero-1280.png")
        assertTrue(mobile in 1..<desktop, "the alternative has to come first, or it is never picked: $html")
        assertTrue(html.contains("""media="(max-width: 600px)""""), html)
        // 4:5 of 320 is 400 - the alternative reserves its own box, not the 16:9 one
        assertTrue(html.contains("""width="320" height="400""""), html)
        // and the default is 16:9 at 1280
        assertTrue(html.contains("""width="1280" height="720""""), html)
    }

    @Test
    fun `a crop narrows the ladder to what the image can fill`() {
        val setup = setup()
        // 4000x3000 cut to 4:5 leaves 2400 px of width, so 2560 cannot be served
        val tall = setup.images.template("tall", widths = setOf(640, 2560), sizes = "100vw", crop = Crop(4, 5))
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(tall, setup.described(ImageSidecar(4000, 3000)), alt = "A photo") } }

        assertTrue(html.contains("tall-640.png 640w"), html)
        assertFalse(html.contains("2560w"), html)
        assertTrue(html.contains("""width="640" height="800""""), html)
    }

    @Test
    fun `a template with no alternatives and one format is still a bare img`() {
        val setup = setup()
        val square = setup.images.template("square", widths = setOf(320), sizes = "48px", crop = Crop(1, 1))
        val support = WebSupport(setup.klerk, { _, _ -> actor })

        val html = createHTML().div { with(support) { image(square, setup.described(ImageSidecar(800, 400)), alt = "A photo") } }

        assertFalse(html.contains("<picture"), html)
        // a square out of 800x400 is 400x400, so 320 is servable and stays square
        assertTrue(html.contains("""width="320" height="320""""), html)
    }

    @Test
    fun `an alternative cannot take a name that is already taken`() {
        val setup = setup()
        setup.images.template("card", widths = setOf(320), sizes = "320px") {
            on("small", media = "(max-width: 600px)", widths = setOf(160), sizes = "160px")
        }

        assertFailsWith<IllegalArgumentException> {
            setup.images.template("card-small", widths = setOf(160), sizes = "160px")
        }
    }

    @Test
    fun `two templates cannot share a name`() {
        val setup = setup()

        assertFailsWith<IllegalArgumentException> {
            setup.images.template("gallery", widths = setOf(100), sizes = "100vw")
        }
    }

    @Test
    fun `a template name has to survive being in a URL`() {
        val setup = setup()

        assertFailsWith<IllegalArgumentException> {
            setup.images.template("Hero Image", widths = setOf(100), sizes = "100vw")
        }
    }

    @Test
    fun `the image is described while it is attached, before any variant exists`() = runBlocking {
        val setup = setup()
        testImagePlugin = setup.images
        try {
            setup.klerk.meta.start(installShutdownHook = false)
            val blob = setup.klerk.attachedData.prepare(png(800, 400).inputStream(), DescribedImage::class, actor)
            setup.klerk.attachedData.awaitProcessing(blob)
            val photoID = setup.klerk.handle(
                Command(event = CreatePhoto, model = null, params = CreatePhotoParams(DescribedImage(blob))),
                actor,
                ProcessingOptions(CommandToken.simple()),
            ).orThrow().primaryModel as ModelID<Photo>

            val meta = setup.klerk.read(actor) { attachedData.metadata(get(photoID).props.image.id) }

            val sidecar = assertNotNull(
                setup.images.sidecar(meta.id, meta.hash),
                "the step should have described the image",
            )
            assertEquals(800, sidecar.width)
            assertEquals(400, sidecar.height)
            setup.klerk.meta.stop()
        } finally {
            testImagePlugin = null
        }
    }
}
