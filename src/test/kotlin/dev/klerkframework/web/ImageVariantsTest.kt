package dev.klerkframework.web

import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.web.attached.attachedDataRoutes
import dev.klerkframework.web.config.*
import dev.klerkframework.web.image.Crop
import dev.klerkframework.web.image.ImageLimits
import dev.klerkframework.web.image.ImagePlugin
import dev.klerkframework.web.image.ImageRefused
import dev.klerkframework.web.image.ImageIoProcessor
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Serving images in the size a page asked for. What matters is what the browser sees on a cold cache — a usable
 * image that no CDN may keep — and that the number of images that can ever be generated is finite.
 */
class ImageVariantsTest {

    private lateinit var klerk: Klerk<Context, MyCollections>
    private lateinit var images: ImagePlugin<Context, MyCollections>
    private lateinit var variantDirectory: Path

    private val author = Context(CustomIdentity(id = null, externalId = 1))

    /** A PNG big enough that scaling it down is a real operation. */
    private fun png(width: Int = 800, height: Int = 600): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = Color(30, 120, 200)
            fillRect(0, 0, width, height)
            paint = Color.WHITE
            fillOval(width / 4, height / 4, width / 2, height / 2)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun ApplicationTestBuilder.setup(
        widths: Set<Int> = setOf(320, 640),
        maxVariantBytes: Long? = null,
        serveOriginalImages: Boolean = false,
    ) {
        System.setProperty("DEVELOPMENT_MODE", "true")
        variantDirectory = Files.createTempDirectory("klerk-variants")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        images = ImagePlugin(
            variantDirectory,
            formats = setOf("png"),
            processor = ImageIoProcessor(),
            maxVariantBytes = maxVariantBytes,
        )
        images.template("cover", widths = widths, sizes = "320px") {
            on("square", media = "(max-width: 600px)", widths = setOf(320), sizes = "320px", crop = Crop(1, 1))
        }
        klerk = Klerk.create(createConfig(collections).withPlugin(images), testSettings())
        val support = WebSupport(klerk, { _, _ -> author })
        application {
            routing {
                attachedDataRoutes(support, images = images, serveOriginalImages = serveOriginalImages)
            }
        }
    }

    /** A publication whose cover is a real image. */
    private suspend fun createPublication(): ModelID<Publication> {
        val cover = klerk.attachedData.prepare(png().inputStream(), PublicCover::class, author)
        val body = klerk.attachedData.prepare("a body", PublicBody::class, author)
        val result = klerk.handle(
            Command(
                event = CreatePublication,
                model = null,
                params = CreatePublicationParams(PublicCover(cover), PublicBody(body), null),
            ),
            author,
            ProcessingOptions(CommandToken.simple()),
        )
        @Suppress("UNCHECKED_CAST")
        return requireNotNull(result.orThrow().primaryModel) as ModelID<Publication>
    }

    private suspend fun coverPath(id: ModelID<Publication>, variant: String?): String {
        val cover = klerk.read(author) { get(id).props.cover }
        val meta = klerk.attachedData.getMetadata(cover.id, author)
        return DefaultPathProvider().attachedDataPath(cover.id, meta.hash, variant)
    }

    /** Waits for the generation job, which runs on its own schedule. */
    private suspend fun awaitVariant(width: Int) = awaitFile("$width.png")

    private suspend fun awaitFile(name: String) {
        repeat(100) {
            if (listing().contains(name)) {
                return
            }
            delay(100)
        }
        error("$name was never generated; the directory holds ${listing()}")
    }

    private fun listing(): List<String> = filesUnder(variantDirectory)

    /** The `id-hash` directory one publication's cover lives in. */
    private suspend fun directoryOf(id: ModelID<Publication>): String {
        val cover = klerk.read(author) { get(id).props.cover }
        return "${cover.id}-${klerk.attachedData.getMetadata(cover.id, author).hash}"
    }

    @Test
    fun `a format the template does not declare is a 404, even when the plugin allows it`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        variantDirectory = Files.createTempDirectory("klerk-variants")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        images = ImagePlugin(variantDirectory, formats = setOf("png", "jpeg"), processor = ImageIoProcessor())
        images.template("cover", widths = setOf(320), sizes = "320px", formats = setOf("png"))
        klerk = Klerk.create(createConfig(collections).withPlugin(images), testSettings())
        val support = WebSupport(klerk, { _, _ -> author })
        application { routing { attachedDataRoutes(support, images = images) } }
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        // The plugin lists jpeg, but this template does not offer it - so it is not a variant this route serves.
        assertEquals(HttpStatusCode.NotFound, client.get(coverPath(id, "cover-320.jpeg")).status)
        assertEquals(HttpStatusCode.OK, client.get(coverPath(id, "cover-320.png")).status)
        klerk.meta.stop()
    }

    @Test
    fun `the first request waits for the variant rather than being given the original`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val path = coverPath(id, "cover-320.png")

        val cold = client.get(path)

        assertEquals(HttpStatusCode.OK, cold.status)
        // The real variant, cacheable like any other - nothing stands in for it, so nothing has to be kept out of a cache.
        assertEquals("public, max-age=2419200, immutable", cold.headers[HttpHeaders.CacheControl])
        val bytes = cold.readRawBytes()
        assertTrue(bytes.size < png().size, "expected the variant, not the original")
        assertEquals(320, ImageIO.read(bytes.inputStream()).width)
        klerk.meta.stop()
    }

    @Test
    fun `once generated, the variant is smaller and cacheable`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val path = coverPath(id, "cover-320.png")

        client.get(path)
        awaitVariant(320)

        val warm = client.get(path)
        assertEquals(HttpStatusCode.OK, warm.status)
        assertEquals("public, max-age=2419200, immutable", warm.headers[HttpHeaders.CacheControl])
        assertEquals(ContentType.Image.PNG, warm.contentType())
        assertEquals("nosniff", warm.headers["X-Content-Type-Options"])
        val bytes = warm.readRawBytes()
        assertTrue(bytes.size < png().size, "expected the variant to be smaller than the original")
        assertEquals(320, ImageIO.read(bytes.inputStream()).width)
        klerk.meta.stop()
    }

    @Test
    fun `a width outside the allow-list is a 404 and generates nothing`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        val response = client.get(coverPath(id, "cover-999.png"))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(listing().none { it == "999.png" }, "nothing outside the allow-list may be generated")
        klerk.meta.stop()
    }

    @Test
    fun `an alternative is served in its own shape, from its own file`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val path = coverPath(id, "cover-square-320.png")

        client.get(path)
        awaitFile("320-1x1c.png")

        val warm = client.get(path)
        assertEquals(HttpStatusCode.OK, warm.status)
        assertEquals("public, max-age=2419200, immutable", warm.headers[HttpHeaders.CacheControl])
        val image = ImageIO.read(warm.readRawBytes().inputStream())
        // the source is 800x600, so a square is 600x600, and 320 was asked for
        assertEquals(320, image.width)
        assertEquals(320, image.height)
        // the uncropped ladder is untouched: a crop does not overwrite the file the default rendition uses
        assertTrue(listing().none { it == "320.png" }, listing().toString())
        klerk.meta.stop()
    }

    @Test
    fun `a cropped variant arrives in the shape it was asked for, first time`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        // an uncropped 640 exists and is larger, and is still not an answer to a request for a 320 square
        client.get(coverPath(id, "cover-640.png"))
        awaitFile("640.png")
        val cold = client.get(coverPath(id, "cover-square-320.png"))

        assertEquals("public, max-age=2419200, immutable", cold.headers[HttpHeaders.CacheControl])
        val served = ImageIO.read(cold.readRawBytes().inputStream())
        assertEquals(320, served.width)
        assertEquals(320, served.height, "the crop is square from the very first request")
        klerk.meta.stop()
    }

    @Test
    fun `a width the alternative does not offer is a 404, even when the template does`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        // 640 is on the default ladder, but the square alternative only offers 320
        val response = client.get(coverPath(id, "cover-square-640.png"))

        assertEquals(HttpStatusCode.NotFound, response.status)
        klerk.meta.stop()
    }

    @Test
    fun `a template nobody declared is a filename, not a variant`() = testApplication {
        setup(serveOriginalImages = true)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        // the last segment may be anything - a download's name - and only a registered template makes it a variant
        val response = client.get(coverPath(id, "holiday-320.png"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(png().size, response.readRawBytes().size, "expected the original, not a generated variant")
        assertTrue(listing().none { it == "320.png" }, "an unknown template may not generate anything")
        klerk.meta.stop()
    }

    @Test
    fun `the original of an image is not served unless the application asked for it`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        // No variant segment at all, and a segment that is only a download's name: neither reaches the original,
        // which is the copy that still carries whatever the camera wrote into it.
        assertEquals(HttpStatusCode.NotFound, client.get(coverPath(id, null)).status)
        assertEquals(HttpStatusCode.NotFound, client.get(coverPath(id, "holiday-320.png")).status)
        // A declared variant is still served.
        assertEquals(HttpStatusCode.OK, client.get(coverPath(id, "cover-320.png")).status)
        klerk.meta.stop()
    }

    @Test
    fun `a larger variant does not stand in for the one that was asked for`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        client.get(coverPath(id, "cover-640.png"))
        awaitVariant(640)
        val cold = client.get(coverPath(id, "cover-320.png"))

        assertEquals("public, max-age=2419200, immutable", cold.headers[HttpHeaders.CacheControl])
        assertEquals(320, ImageIO.read(cold.readRawBytes().inputStream()).width)
        klerk.meta.stop()
    }

    @Test
    fun `many simultaneous misses schedule one job, not many`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val path = coverPath(id, "cover-320.png")

        coroutineScope { (1..10).map { async { client.get(path) } }.awaitAll() }

        val scheduled = klerk.jobs.getAllJobs(author).count { it.name.value == "klerk-web-image-variant" }
        assertEquals(1, scheduled, "ten misses should mean one job")
        klerk.meta.stop()
    }

    @Test
    fun `the sweep evicts the oldest images to stay within the budget`() = testApplication {
        // A budget of one byte: everything is over it, so the sweep keeps evicting until nothing is left but the
        // newest image it could not get below the budget without.
        setup(maxVariantBytes = 1)
        klerk.meta.start(installShutdownHook = false)
        val older = createPublication()
        client.get(coverPath(older, "cover-320.png"))
        awaitVariant(320)
        val olderDirectory = variantDirectory.resolve(directoryOf(older))
        assertTrue(Files.isDirectory(olderDirectory))

        images.sweep()

        assertTrue(Files.notExists(olderDirectory), "the oldest image should have been evicted: ${listing()}")
        // It is a cache, so asking again simply makes it afresh.
        assertEquals(HttpStatusCode.OK, client.get(coverPath(older, "cover-320.png")).status)
        assertTrue(Files.isDirectory(olderDirectory))
        klerk.meta.stop()
    }

    @Test
    fun `an unset budget leaves everything alone`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        client.get(coverPath(id, "cover-320.png"))
        awaitVariant(320)

        images.sweep()

        assertTrue(listing().any { it == "320.png" }, "nothing should be evicted without a budget")
        klerk.meta.stop()
    }
    @Test
    fun `the sweep deletes what belongs to data that is gone`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        client.get(coverPath(id, "cover-320.png"))
        awaitVariant(320)
        assertTrue(listing().any { it == "320.png" })

        klerk.handle(
            Command(event = DeletePublication, model = id, params = null),
            author,
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()
        images.sweep()

        assertTrue(listing().none { it == "320.png" }, "the variants outlived their blob: ${listing()}")
        klerk.meta.stop()
    }

    @Test
    fun `a non-image is still served as the download it was`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val body = klerk.read(author) { get(id).props.body }
        val meta = klerk.attachedData.getMetadata(body.id, author)

        val response = client.get(DefaultPathProvider().attachedDataPath(body.id, meta.hash, "cover-320.png"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("a body", response.bodyAsText())
        klerk.meta.stop()
    }

    @Test
    fun `an image larger than the pixel limit is refused before it is decoded`() = runBlocking {
        val directory = Files.createTempDirectory("klerk-image-limit")
        val source = directory.resolve("big.png")
        Files.write(source, png(width = 200, height = 200))
        val processor = ImageIoProcessor(ImageLimits(maxPixels = 1000))

        assertNotNull(processor.probe(source))
        val refused = assertFailsWith<ImageRefused> {
            processor.render(source, directory.resolve("out.png"), 100, "png")
        }
        assertTrue(refused.message!!.contains("40000 pixels"))
    }
}
