package dev.klerkframework.web

import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.assets.AssetsPlugin
import dev.klerkframework.web.assets.ImageAsset
import dev.klerkframework.web.config.*
import dev.klerkframework.web.image.Crop
import dev.klerkframework.web.image.ImageIoProcessor
import dev.klerkframework.web.image.ImagePlugin
import dev.klerkframework.web.image.ImageTemplate
import dev.klerkframework.web.image.image
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A picture that ships with the application, served through the same templates as an uploaded one. What is
 * different about it is that it is public, and that it was measured before the first request.
 */
class ImageAssetTest {

    private val actor = Context(CustomIdentity(id = null, externalId = 1))

    private lateinit var klerk: Klerk<Context, MyCollections>
    private lateinit var images: ImagePlugin<Context, MyCollections>
    private lateinit var variantDirectory: Path
    private lateinit var splash: ImageAsset
    private lateinit var banner: ImageTemplate<Context, MyCollections>

    private fun ApplicationTestBuilder.setup(): String {
        System.setProperty("DEVELOPMENT_MODE", "true")
        variantDirectory = Files.createTempDirectory("klerk-asset-variants")
        images = ImagePlugin(variantDirectory, formats = setOf("png"), processor = ImageIoProcessor())
        banner = images.template("banner", widths = setOf(200, 400), sizes = "400px")
        splash = ImageAsset("splash.png")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        klerk = Klerk.create(
            createConfig(collections, assets = AssetsPlugin(setOf(splash), images = images)).withPlugin(images),
            testSettings(),
        )
        val web = KlerkWeb(klerk, { _, _ -> actor }, canSeeAdminUI = { true })
        application { routing { klerkWebRoutes(web) } }
        return "/_assets"
    }

    private fun html(): String {
        val support = WebSupport(klerk, { _, _ -> actor })
        return createHTML().div { with(support) { image(banner, splash, alt = "A splash") } }
    }

    @Test
    fun `an asset renders through a template, measured before anything asked for it`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)

        val rendered = html()

        // 600x400, so the 400-wide variant is 400x267 - and the page knows that on the very first render, because
        // the asset was measured when the application started.
        assertTrue(rendered.contains("""width="400""""), rendered)
        assertTrue(rendered.contains("""height="267""""), rendered)
        assertTrue(rendered.contains("/_assets/splash.png_"), rendered)
        assertTrue(rendered.contains("banner-200.png 200w"), rendered)
        assertTrue(rendered.contains("banner-400.png 400w"), rendered)
        klerk.meta.stop()
    }

    @Test
    fun `a variant of an asset is generated, served and cached forever`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val url = Regex("""/_assets/splash\.png_[^/\"]+/banner-200\.png""").find(html())!!.value

        val response = client.get(url)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("max-age=31536000, public, immutable", response.headers[HttpHeaders.CacheControl])
        val bytes = response.readRawBytes()
        assertEquals(200, ImageIO.read(bytes.inputStream()).width)
        klerk.meta.stop()
    }

    @Test
    fun `a width the template does not offer is a 404`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val base = Regex("""/_assets/splash\.png_[^/\"]+""").find(html())!!.value

        assertEquals(HttpStatusCode.NotFound, client.get("$base/banner-999.png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/nosuchtemplate-200.png").status)
        klerk.meta.stop()
    }

    @Test
    fun `the asset itself is still served, with the hash in its URL`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val base = Regex("""/_assets/splash\.png_[^/\"]+""").find(html())!!.value

        val response = client.get(base)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType())
        assertEquals("max-age=31536000, public, immutable", response.headers[HttpHeaders.CacheControl])
        klerk.meta.stop()
    }

    @Test
    fun `the sweep keeps the registered asset and drops the rest`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val url = Regex("""/_assets/splash\.png_[^/\"]+/banner-200\.png""").find(html())!!.value
        client.get(url)
        // A directory belonging to an asset nobody registered - what a redeploy that changed the image leaves behind.
        images.store.writeSidecar("asset", "goneGoneGon", dev.klerkframework.web.image.ImageSidecar(1, 1))

        images.sweep()

        val left = filesUnder(variantDirectory)
        assertTrue(left.any { it == "200.png" }, "the registered asset should survive: $left")
        assertTrue(
            Files.notExists(variantDirectory.resolve("asset-goneGoneGon")),
            "an asset that is no longer registered should be swept",
        )
        klerk.meta.stop()
    }

    @Test
    fun `an asset that is not an image at all is refused when it is declared`() {
        assertFailsWith<IllegalArgumentException> { ImageAsset("water.css") }
    }

    @Test
    fun `a missing resource fails the startup, naming the asset`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val plugin = ImagePlugin<Context, MyCollections>(
            Files.createTempDirectory("klerk-asset-missing"),
            formats = setOf("png"),
            processor = ImageIoProcessor(),
        )
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val k = Klerk.create(
            createConfig(
                collections,
                assets = AssetsPlugin(setOf(ImageAsset("nosuchfile.png")), images = plugin),
            ).withPlugin(plugin),
            testSettings(),
        )

        val thrown = assertFailsWith<IllegalStateException> { k.meta.start(installShutdownHook = false) }

        assertTrue(thrown.message!!.contains("nosuchfile.png"), thrown.message)
    }
}