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
import dev.klerkframework.web.image.ImageInfo
import dev.klerkframework.web.image.ImageIoProcessor
import dev.klerkframework.web.image.ImageLimits
import dev.klerkframework.web.image.ImagePlugin
import dev.klerkframework.web.image.ImageProcessor
import dev.klerkframework.web.image.ImageRefused
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.ktor.client.request.*
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The contract [ImageProcessor] implementations are written against, exercised through a fake one: how many times a
 * variant costs, what an implementation reports, and which failures are worth retrying.
 */
class ImageProcessorContractTest {

    private lateinit var klerk: Klerk<Context, MyCollections>
    private lateinit var variantDirectory: Path

    private val author = Context(CustomIdentity(id = null, externalId = 1))

    /** What the fake claims every original is, chosen not to be the size of the file it is handed. */
    private val reported = ImageInfo(1234, 567)

    private class Fake(
        private val reported: ImageInfo,
        override val limits: ImageLimits = ImageLimits(),
        private val onVerify: () -> Unit = {},
        private val onRender: (Int) -> Unit = {},
    ) : ImageProcessor {

        override val outputFormats: Set<String> = setOf("png")

        val probes = AtomicInteger()
        val renders = AtomicInteger()

        override suspend fun verify() = onVerify()

        override suspend fun probe(source: Path): ImageInfo? {
            probes.incrementAndGet()
            return reported
        }

        override suspend fun stripMetadata(source: Path, target: Path): ImageInfo {
            onRender(renders.incrementAndGet())
            Files.write(target, ByteArray(32) { 3 })
            return reported
        }

        override suspend fun render(source: Path, target: Path, width: Int, format: String, crop: Crop?): ImageInfo {
            onRender(renders.incrementAndGet())
            Files.write(target, ByteArray(64) { 7 })
            return reported
        }
    }

    private fun png(width: Int = 800, height: Int = 600): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = Color(30, 120, 200)
            fillRect(0, 0, width, height)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun ApplicationTestBuilder.setup(
        processor: ImageProcessor,
        renderWait: Duration = 5.seconds,
        registry: MeterRegistry? = null,
    ) {
        System.setProperty("DEVELOPMENT_MODE", "true")
        variantDirectory = Files.createTempDirectory("klerk-contract-variants")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val images = ImagePlugin<Context, MyCollections>(
            variantDirectory,
            formats = setOf("png"),
            processor = processor,
            renderWait = renderWait,
        )
        images.template("cover", widths = setOf(320, 640), sizes = "320px")
        klerk = Klerk.create(
            createConfig(collections).withPlugin(images),
            registry?.let { testSettings().copy(meterRegistry = it) } ?: testSettings(),
        )
        val support = WebSupport(klerk, { _, _ -> author })
        application {
            routing {
                attachedDataRoutes(support, images = images)
            }
        }
    }

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

    private suspend fun coverPath(id: ModelID<Publication>, variant: String): String {
        val cover = klerk.read(author) { get(id).props.cover }
        val meta = klerk.attachedData.getMetadata(cover.id, author)
        return DefaultPathProvider().attachedDataPath(cover.id, meta.hash, variant)
    }

    private fun files(): List<String> = filesUnder(variantDirectory)

    private suspend fun awaitFile(name: String, attempts: Int = 150) {
        repeat(attempts) {
            if (files().contains(name)) {
                return
            }
            delay(100)
        }
        error("$name was never generated; the directory holds ${files()}")
    }

    private fun sidecar(): String = fileUnder(variantDirectory, "meta.json")?.let { Files.readString(it) } ?: ""

    @Test
    fun `generating a variant renders once and never probes`() = testApplication {
        val processor = Fake(reported)
        setup(processor)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        client.get(coverPath(id, "cover-320.png"))
        awaitFile("320.png")

        assertEquals(1, processor.renders.get())
        // A separate probe would be a second roundtrip for a processor that has to leave this JVM.
        assertEquals(0, processor.probes.get())
        klerk.meta.stop()
    }

    @Test
    fun `the size the render reports is what the page is laid out from`() = testApplication {
        val processor = Fake(reported)
        setup(processor)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        client.get(coverPath(id, "cover-320.png"))
        awaitFile("320.png")

        // Not the size of the PNG that was actually attached: it came from the processor.
        assertEquals("""{"width":1234,"height":567}""", sidecar())
        klerk.meta.stop()
    }

    @Test
    fun `an image the processor refuses is never rendered again`() = testApplication {
        val processor = Fake(reported, onRender = { throw ImageRefused("not an image") })
        setup(processor)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        client.get(coverPath(id, "cover-320.png"))
        // Long enough that a retry would have happened; the job backs off for about three seconds.
        delay(6000)

        assertEquals(1, processor.renders.get())
        assertTrue(files().none { it == "320.png" }, "the variant should not exist: ${files()}")
        klerk.meta.stop()
    }

    @Test
    fun `a processor that is merely unavailable is retried`() = testApplication {
        val processor = Fake(reported, onRender = { attempt ->
            if (attempt == 1) throw IOException("the transformer is restarting")
        })
        setup(processor)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        client.get(coverPath(id, "cover-320.png"))
        awaitFile("320.png")

        assertTrue(processor.renders.get() >= 2, "expected a retry, got ${processor.renders.get()} renders")
        klerk.meta.stop()
    }

    @Test
    fun `a refused variant is a 404, and the original is never sent instead`() = testApplication {
        val processor = Fake(reported, onRender = { throw ImageRefused("not an image") })
        setup(processor)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val path = coverPath(id, "cover-320.png")

        val first = client.get(path)

        assertEquals(HttpStatusCode.NotFound, first.status)
        assertTrue(first.readRawBytes().size < png().size, "the original must never be served under a variant URL")

        // The second request is answered from the negative cache, so it does not wait for a job that cannot succeed.
        val started = System.currentTimeMillis()
        assertEquals(HttpStatusCode.NotFound, client.get(path).status)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 1000, "a known-refused variant should answer at once, took ${elapsed}ms")
        assertEquals(1, processor.renders.get(), "it should not have been rendered again")
        klerk.meta.stop()
    }

    @Test
    fun `a variant that is not ready in time is a 503, not something else`() = testApplication {
        val processor = Fake(reported, onRender = { Thread.sleep(3000) })
        setup(processor, renderWait = 300.milliseconds)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        val response = client.get(coverPath(id, "cover-320.png"))

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        klerk.meta.stop()
    }
    @Test
    fun `renders, refusals and waits are published to the meter registry`() = testApplication {
        val registry = SimpleMeterRegistry()
        val processor = Fake(reported)
        setup(processor, registry = registry)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        client.get(coverPath(id, "cover-320.png"))
        awaitFile("320.png")

        assertEquals(1L, registry.timer("klerk.web.image.render").count())
        assertEquals(1L, registry.timer("klerk.web.image.wait").count())
        assertEquals(0.0, registry.counter("klerk.web.image.refused").count())
        assertEquals(0.0, registry.counter("klerk.web.image.unavailable").count())
        assertTrue(
            assertNotNull(registry.find("klerk.web.image.bytes").gauge()).value() > 0.0,
            "the variant directory holds something",
        )
        klerk.meta.stop()
    }

    @Test
    fun `a refusal and a timeout are counted`() = testApplication {
        val registry = SimpleMeterRegistry()
        setup(Fake(reported, onRender = { throw ImageRefused("not an image") }), registry = registry)
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()

        client.get(coverPath(id, "cover-320.png"))

        assertEquals(1.0, registry.counter("klerk.web.image.refused").count())
        // Refused is a 404, not a 503 - the request did not give up waiting, it was told no.
        assertEquals(0.0, registry.counter("klerk.web.image.unavailable").count())
        klerk.meta.stop()
    }
    @Test
    fun `a processor that refuses to start stops the application`() = testApplication {
        setup(Fake(reported, onVerify = { throw IllegalStateException("the transformer did not answer") }))

        val thrown = assertFails { klerk.meta.start(installShutdownHook = false) }

        assertTrue(
            generateSequence(thrown) { it.cause }.any { it.message?.contains("did not answer") == true },
            "expected the verify failure to surface, got $thrown",
        )
    }

    @Test
    fun `ImageIoProcessor warns once, not once per render`() {
        val captured = ByteArrayOutputStream()
        val original = System.err
        val processor = try {
            System.setErr(PrintStream(captured, true))
            val processor = ImageIoProcessor()
            val source = Files.createTempFile("klerk-warn-", ".png")
            Files.write(source, png())
            kotlinx.coroutines.runBlocking {
                repeat(3) {
                    processor.render(source, Files.createTempFile("klerk-warn-out-", ".png"), 320, "png", null)
                }
            }
            processor
        } finally {
            System.setErr(original)
        }

        val warnings = captured.toString().split("ImageIoProcessor is meant for development").size - 1
        assertEquals(1, warnings, "the warning belongs to the constructor, not to every render")
        assertEquals(setOf("jpeg", "png"), processor.outputFormats)
    }
}
