package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.attached.attachedDataRoutes
import dev.klerkframework.web.config.*
import dev.klerkframework.web.image.Crop
import dev.klerkframework.web.image.FetchPriority
import dev.klerkframework.web.image.ImageIoProcessor
import dev.klerkframework.web.image.ImageLoading
import dev.klerkframework.web.image.ImagePlugin
import dev.klerkframework.web.image.image
import io.ktor.http.HttpStatusCode
import com.google.gson.Gson
import dev.klerkframework.web.image.ImageInfo
import dev.klerkframework.web.image.ImageLimits
import dev.klerkframework.web.image.ImageProcessor
import dev.klerkframework.web.image.ImageRefused
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import io.ktor.client.request.get
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.html.*
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue

private suspend fun ApplicationCall.docCtx(klerk: Klerk<Context, MyCollections>): Context = Context.system()

/**
 * The examples in docs/ must keep compiling. This mirrors them; if an API changes, this fails and the docs get
 * updated with it.
 */
class DocExamplesTest {

    private fun klerk(): Klerk<Context, MyCollections> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        return Klerk.create(createConfig(MyCollections(bc, AuthorCollections(bc.all), ModelViews())), testSettings())
    }

    // docs/model-pages.md
    private class MyPaths : PathProvider by DefaultPathProvider() {
        private val delegate = DefaultPathProvider()
        override fun pathForItem(kClass: KClass<out Any>, id: ModelID<*>): String? =
            if (kClass == Book::class) null else delegate.pathForItem(kClass, id)

        override fun pathForItem(kClass: KClass<out Any>, id: String): String? =
            if (kClass == Book::class) null else delegate.pathForItem(kClass, id)
    }

    // docs/appearance.md
    private val classProvider = CssClassProvider { part, element, _, _ ->
        when {
            part == UiPart.ModelTable && element == "table" -> setOf("striped")
            part == UiPart.Form && element == "input" -> setOf("form-control")
            else -> emptySet()
        }
    }

    @Test
    fun `the documented examples compile and run`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        val author = createAuthorJKRowling(klerk)

        // docs/introduction.md - WebSupport
        val pathProvider = MyPaths()
        val layout = Layout(externalCssPath = "https://example.com/classless.css", lang = "sv")
        val support = WebSupport(klerk, ApplicationCall::docCtx, pathProvider, layout, classProvider)

        // docs/model-pages.md
        val authors = ModelListPage<Author, Context, MyCollections>(
            Author::class, support, pathToList = "/author", humanName = "Authors",
        )
        val authorPage = ModelDetailPage<Author, Context, MyCollections>(
            Author::class,
            support,
            humanName = "Author",
            eventLogPath = "/admin/_eventlog",
            useTable = true,
            extraContent = { _, _ -> { p { +"Anything you like" } } },
        )

        // docs/tables.md
        val columns = listOf(
            Column<Author>("Name") { model -> +model.props.firstName.value },
        ) + Column.defaults<Author>().filter { it.header == "State" }
        val table = TableTemplate(klerk, Author::class, support, columns)

        application {
            routing {
                autoButtonsRoutes(support.autoButtons)
                modelListRoutes(authors)
                modelDetailRoutes(authorPage)

                // docs/model-pages.md - calling render from your own route
                get("/writers") { authors.respond(call) }

                // docs/tables.md
                get("/authors") {
                    val context = call.docCtx(klerk)
                    val built = klerk.read(context) {
                        table.build(klerk.spec.views.authors.all, this, call)
                    }
                    call.respond(klerk.read(context) {
                        html {
                            body { modelTable(built) }
                        }
                    })
                }

                // docs/introduction.md - Ask Klerk, rendered as a whole page
                get("/actions") {
                    val context = call.docCtx(klerk)
                    val (model, events) = klerk.read(context) { Pair(get(author), getPossibleEvents(author)) }
                    support.respondPage(call, "Actions") {
                        h1 { +"Actions for ${model.props.firstName.value}" }
                        events.forEach { event -> eventButton(event, author, context) }
                    }
                }

                // docs/introduction.md and docs/auto-buttons.md - the same block in a fragment
                get("/fragment") {
                    val context = call.docCtx(klerk)
                    val events = klerk.read(context) { getPossibleEvents(author) }
                    call.respond(klerk.read(context) {
                        html {
                            body {
                                with(support) {
                                    events.forEach { event -> eventButton(event, author, context) }
                                }
                            }
                        }
                    })
                }

                // docs/appearance.md - Layout.page for your own page
                get("/own") {
                    call.respondHtml(block = layout.page("My page") {
                        h1 { +"Hello" }
                        script(pathProvider.assetPath("my-script.js")) { defer = true }
                    })
                }
            }
        }

        listOf("/author", "/writers", "/authors", "/actions", "/fragment", "/own").forEach { path ->
            assertTrue(client.get(path).status.value < 400, "$path failed")
        }
    }

    // docs/images.md
    @Test
    fun `the images example compiles`() = testApplication {
        val images = ImagePlugin<Context, MyCollections>(Files.createTempDirectory("klerk-doc-images"), processor = ImageIoProcessor())
        val hero = images.template(
            "hero",
            widths = setOf(640, 1280, 2560),
            sizes = "100vw",
            crop = Crop(16, 9),
            loading = ImageLoading.Eager,
            fetchPriority = FetchPriority.High,
        ) {
            on("mobile", media = "(max-width: 600px)", widths = setOf(320, 640), sizes = "100vw", crop = Crop(4, 5))
        }
        val thumbnail = images.template("thumbnail", widths = setOf(160, 320), sizes = "auto, 160px")
        val avatar = images.template("avatar", widths = setOf(48, 96, 192), sizes = "48px", crop = Crop(1, 1))

        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val klerk = Klerk.create(createConfig(collections).withPlugin(images), testSettings())
        klerk.meta.start()
        val support = WebSupport(klerk, ApplicationCall::docCtx)

        application {
            routing {
                attachedDataRoutes(support, images = images)

                get("/flowers/{id}") {
                    val context = call.docCtx(klerk)
                    val flowerID = ModelID<Flower>(call.parameters["id"]!!.toInt())
                    val found = klerk.read(context) {
                        val flower = getOrNull(flowerID) ?: return@read null
                        flower to attachedData.metadata(flower.props.image.id)
                    }
                    if (found == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val (flower, photo) = found
                    support.respondPage(call, flower.props.name.value) {
                        photo?.let { image(hero, it, alt = "A flower") }
                        // and the same image in other roles, outside respondPage:
                        with(support) {
                            photo?.let { image(thumbnail, it, alt = "A flower") }
                            photo?.let { image(avatar, it, alt = "A flower") }
                        }
                    }
                }
            }
        }

        assertTrue(client.get("/flowers/1").status.value < 500)
    }

    // docs/admin-ui.md
    @Test
    fun `the admin ui example compiles`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        val support = WebSupport(klerk, ApplicationCall::docCtx)

        val adminUI = AdminUI(
            support.withPathProvider(DefaultPathProvider(prefix = "admin/")),
            canSeeAdminUI = { true },
        )

        application { routing { adminUiRoutes(adminUI) } }

        assertTrue(client.get("/admin/").status.value < 400)
    }
}

/**
 * docs/images.md — "Writing one". Never run against a real transformer here; the point is that the implementation
 * the docs tell people to copy still compiles against [ImageProcessor].
 */
@Suppress("unused")
private class TransformerProcessor(
    private val baseUrl: String,
    override val limits: ImageLimits = ImageLimits(),
) : ImageProcessor {

    override val outputFormats: Set<String> = setOf("jpeg", "png", "webp", "avif")

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = limits.timeout.inWholeMilliseconds }
    }

    private data class Metadata(val width: Int, val height: Int)

    override suspend fun verify() {
        val answer = runCatching { client.get("$baseUrl/health") }.getOrNull()
        checkNotNull(answer?.takeIf { it.status.isSuccess() }) { "The image transformer at $baseUrl did not answer" }
    }

    override suspend fun probe(source: Path): ImageInfo? {
        val response = client.get("$baseUrl/meta/${source.fileName}")
        if (!response.status.isSuccess()) {
            return null
        }
        val meta = Gson().fromJson(response.bodyAsText(), Metadata::class.java)
        return ImageInfo(meta.width, meta.height)
    }

    override suspend fun stripMetadata(source: Path, target: Path): ImageInfo {
        val response = client.get("$baseUrl/strip/${source.fileName}")
        when {
            response.status.isSuccess() -> Unit
            response.status.value in 400..499 -> throw ImageRefused("$source was refused: ${response.status}")
            else -> error("The image transformer answered ${response.status}")
        }
        response.bodyAsChannel().toInputStream().use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return ImageInfo(
            checkNotNull(response.headers["X-Source-Width"]).toInt(),
            checkNotNull(response.headers["X-Source-Height"]).toInt(),
        )
    }

    override suspend fun render(source: Path, target: Path, width: Int, format: String, crop: Crop?): ImageInfo {
        val shape = crop?.let { "/crop/${it.width}x${it.height}/${it.gravity}" } ?: ""
        val response = client.get("$baseUrl/fit/$width$shape/${source.fileName}@$format")
        when {
            response.status.isSuccess() -> Unit
            // The transformer enforces the pixel cap, so 4xx covers "too big to decode" as well as "not an image".
            response.status.value in 400..499 -> throw ImageRefused("$source was refused: ${response.status}")
            // Down, restarting, out of workers: worth trying again.
            else -> error("The image transformer answered ${response.status}")
        }
        response.bodyAsChannel().toInputStream().use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return ImageInfo(
            checkNotNull(response.headers["X-Source-Width"]).toInt(),
            checkNotNull(response.headers["X-Source-Height"]).toInt(),
        )
    }
}
