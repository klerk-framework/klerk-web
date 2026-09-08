package dev.klerkframework.web

import dev.klerkframework.klerk.EventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.collection.asSequence
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.web.attached.attachedDataRoutes
import dev.klerkframework.web.config.*
import dev.klerkframework.web.image.Crop
import dev.klerkframework.web.image.Gravity
import dev.klerkframework.web.image.ImageIoProcessor
import dev.klerkframework.web.image.ImagePlugin
import dev.klerkframework.web.image.ImageProcessor
import dev.klerkframework.web.image.image
import dev.klerkframework.web.upload.UploadPlugin
import dev.klerkframework.web.upload.uploadRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.html.a
import kotlinx.html.figure
import kotlinx.html.h1
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole image flow, over HTTP: a file is uploaded, a command attaches it, a page renders it responsively, and the
 * browser fetches the size that page asked for.
 *
 * Everything the pieces are tested for separately is here at once — the upload plugin, the pre-attach steps, the
 * measurement, the markup, the serving route, the fallback and the generation job.
 */
class ImageFlowTest {

    private val actor = Context.swedishUnauthenticated()
    private val csrfToken = "flow-test-token"

    private lateinit var klerk: Klerk<Context, MyCollections>
    private lateinit var images: ImagePlugin<Context, MyCollections>
    private lateinit var uploads: UploadPlugin<Context, MyCollections>

    private fun processor(): ImageProcessor = ImageIoProcessor()

    private fun photograph(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = Color(20, 110, 60)
            fillRect(0, 0, width, height)
            paint = Color(240, 200, 60)
            fillOval(width / 5, height / 5, width / 2, height / 2)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun ApplicationTestBuilder.setup() {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        uploads = UploadPlugin(Files.createTempDirectory("klerk-flow-uploads"))
        images = ImagePlugin(
            variantDirectory = Files.createTempDirectory("klerk-flow-variants"),
            formats = setOf("png"),
            processor = processor(),
        )
        val thumbnail = images.template("thumbnail", widths = flowerImageWidths, sizes = "320px") {
            on(
                "portrait",
                media = "(max-width: 600px)",
                widths = flowerImageWidths,
                sizes = "100vw",
                crop = Crop(4, 5, gravity = Gravity.North),
            )
        }
        testImagePlugin = images
        klerk = Klerk.create(
            createConfig(collections).withPlugin(uploads).withPlugin(images),
            testSettings(),
        )
        val support = WebSupport(klerk, { _, _ -> actor })
        val layout = Layout()
        val form = FormTemplate(
            EventWithParameters(CreateFlower.id, EventParameters(CreateFlowerParams::class)),
            klerk,
            postPath = "/flowers",
            pathProvider = DefaultPathProvider(),
            uploads = uploads,
        ) {
            text(CreateFlowerParams::name)
            file(CreateFlowerParams::image)
        }

        application {
            routing {
                uploadRoutes(support, uploads)
                attachedDataRoutes(support, images = images)

                post("/flowers") {
                    when (val parsed = form.parse(call, actor)) {
                        is ParseResult.Forbidden -> call.respond(HttpStatusCode.Forbidden)
                        is ParseResult.Invalid -> call.respond(
                            HttpStatusCode.BadRequest,
                            parsed.problems.joinToString { it.endUserTranslatedMessage },
                        )
                        is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
                        is ParseResult.Parsed -> {
                            klerk.handle(
                                Command(CreateFlower, null, parsed.params),
                                actor,
                                ProcessingOptions(parsed.key),
                            ).orThrow()
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                get("/flowers") {
                    val flowers = klerk.read(actor) {
                        views.flowers.all.asSequence().toList().map { it to attachedData.metadata(it.props.image.id) }
                    }
                    call.respondHtml(block = layout.page("Flowers") {
                        h1 { +"Flowers" }
                        flowers.forEach { (flower, photo) ->
                            figure {
                                a(href = "#") {
                                    with(support) {
                                        photo?.let { image(thumbnail, it, alt = flower.props.name.value) }
                                    }
                                }
                            }
                        }
                    })
                }
            }
        }
    }

    /** Uploads the bytes the way the browser's script does, then submits the form carrying the upload's id. */
    private suspend fun plantFlower(client: io.ktor.client.HttpClient, bytes: ByteArray): HttpResponse {
        val upload = uploads.create(actor, "flower.png", "image/png", bytes.size.toLong())
        uploads.append(actor, upload, 0, bytes.inputStream())
        return client.post("/flowers") {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    Csrf.TOKEN_NAME to csrfToken,
                    IDEMPOTENCE_KEY to CommandToken.simple().toString(),
                    "name" to "Sunflower",
                    "image" to upload.value.toString(),
                ).formUrlEncode()
            )
        }
    }


    @Test
    fun `a flower is uploaded, rendered responsively, and served at the size the page asked for`() = testApplication {
        setup()
        try {
            klerk.meta.start(installShutdownHook = false)

            val planted = plantFlower(client, photograph(1600, 1200))
            assertEquals(HttpStatusCode.OK, planted.status, planted.bodyAsText())

            // The page offers every declared size, and knows the shape of the image before anything was generated —
            // that is the pre-attach step having measured it.
            val page = client.get("/flowers").bodyAsText()
            val widest = flowerImageWidths.max()
            flowerImageWidths.forEach { assertTrue(page.contains("${it}w"), page) }
            assertTrue(page.contains("""width="$widest""""), page)
            // 1600x1200 is 4:3, so the height follows from the width
            assertTrue(page.contains("""height="${widest * 3 / 4}""""), page)

            val smallest = flowerImageWidths.min()
            val url = assertNotNull(Regex("""/_attached/\d+/[^/"]+/thumbnail-$smallest\.png""").find(page)).value

            // The first request waits for the variant and gets it - cacheable, and the size that was asked for.
            val served = client.get(url)
            assertEquals(HttpStatusCode.OK, served.status)
            assertEquals("public, max-age=2419200, immutable", served.headers[HttpHeaders.CacheControl])
            assertEquals(smallest, ImageIO.read(served.readRawBytes().inputStream()).width)

            // Art direction: the narrow viewport is offered a different crop of the same upload, ahead of the <img>.
            assertTrue(page.contains("""media="(max-width: 600px)""""), page)
            val portrait = assertNotNull(
                Regex("""/_attached/\d+/[^/"]+/thumbnail-portrait-$smallest\.png""").find(page),
            ).value
            assertTrue(page.indexOf(portrait) < page.indexOf("<img"), "the alternative has to come first: $page")

            val cropped = ImageIO.read(client.get(portrait).readRawBytes().inputStream())
            assertEquals(smallest, cropped.width)
            assertEquals(smallest * 5 / 4, cropped.height, "the phone gets 4:5, not the 4:3 it was uploaded as")

            klerk.meta.stop()
        } finally {
            testImagePlugin = null
        }
    }
}
