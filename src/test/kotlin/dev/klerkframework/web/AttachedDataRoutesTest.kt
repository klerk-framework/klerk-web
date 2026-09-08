package dev.klerkframework.web

import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.web.attached.attachedDataRoutes
import dev.klerkframework.web.attached.defaultAttachedDataCacheControl
import dev.klerkframework.web.config.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serving attached data over HTTP. What matters here is what a caller sees: that the hash is part of the identity of
 * a URL, that a value nobody may read is indistinguishable from one that does not exist, and that the response says
 * what may be done with the bytes.
 */
class AttachedDataRoutesTest {

    /** A one-pixel PNG, so that Klerk recognises a content type worth serving inline. */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
    )

    private val author = Context(CustomIdentity(id = null, externalId = 1))
    private val stranger = Context(CustomIdentity(id = null, externalId = 2))

    private lateinit var klerk: Klerk<Context, MyCollections>

    /** Serves as [actor], so that a test can decide who is asking. */
    private fun ApplicationTestBuilder.setup(
        actor: Context = author,
        cacheControl: (AttachedDataMetadata) -> String = defaultAttachedDataCacheControl,
    ) {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        klerk = Klerk.create(createConfig(collections), testSettings())
        val support = WebSupport(klerk, { _, _ -> actor }, attachedDataCacheControl = cacheControl)
        application {
            routing {
                attachedDataRoutes(support)
                modelDetailRoutes(ModelDetailPage<Publication, Context, MyCollections>(
                    Publication::class, support, "Publication"
                ))
            }
        }
    }

    /** A publication with a public cover, a public body and a private draft, all attached to one model. */
    private suspend fun createPublication(): ModelID<Publication> {
        val cover = klerk.attachedData.prepare(png.inputStream(), PublicCover::class, author)
        val body = klerk.attachedData.prepare("the body, for everybody", PublicBody::class, author)
        val draft = klerk.attachedData.prepare("not finished yet", SecretDraft::class, author)
        val result = klerk.handle(
            Command(
                event = CreatePublication,
                model = null,
                params = CreatePublicationParams(PublicCover(cover), PublicBody(body), SecretDraft(draft)),
            ),
            author,
            ProcessingOptions(CommandToken.simple()),
        )
        @Suppress("UNCHECKED_CAST")
        return requireNotNull(result.orThrow().primaryModel) as ModelID<Publication>
    }

    @Test
    fun `a public blob is served inline, cached, and never sniffed`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val cover = klerk.read(author) { get(id).props.cover }
        val meta = klerk.attachedData.getMetadata(cover.id, author)
        val path = DefaultPathProvider().attachedDataPath(cover.id, meta.hash)

        val response = client.get(path)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("public, max-age=2419200, immutable", response.headers[HttpHeaders.CacheControl])
        assertNull(response.headers[HttpHeaders.ContentDisposition])
        assertEquals(png.size, response.readRawBytes().size)
        klerk.meta.stop()
    }

    @Test
    fun `a public string is served by the same route`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val body = klerk.read(author) { get(id).props.body }
        val meta = klerk.attachedData.getMetadata(body.id, author)

        val response = client.get(DefaultPathProvider().attachedDataPath(body.id, meta.hash))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("the body, for everybody", response.bodyAsText())
        klerk.meta.stop()
    }

    @Test
    fun `something that is not an image is a download, whatever the client does with it`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val body = klerk.read(author) { get(id).props.body }
        val meta = klerk.attachedData.getMetadata(body.id, author)

        val response = client.get(DefaultPathProvider().attachedDataPath(body.id, meta.hash, "the body.txt"))

        assertEquals(ContentType.Application.OctetStream, response.contentType())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        val disposition = response.headers[HttpHeaders.ContentDisposition]
        assertTrue(disposition?.startsWith("attachment") == true, "expected an attachment, got $disposition")
        assertTrue(disposition.contains("the body.txt"))
        klerk.meta.stop()
    }

    @Test
    fun `private data is not cached`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val draft = klerk.read(author) { get(id).props.draft!! }
        val meta = klerk.attachedData.getMetadata(draft.id, author)

        val response = client.get(DefaultPathProvider().attachedDataPath(draft.id, meta.hash))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("not finished yet", response.bodyAsText())
        klerk.meta.stop()
    }

    @Test
    fun `the application decides the cache control from the metadata`() = testApplication {
        setup(cacheControl = { metadata ->
            when (metadata.visibility) {
                AttachedDataVisibility.Public -> "public, max-age=60"
                AttachedDataVisibility.Private -> "private, max-age=${metadata.size}, immutable"
            }
        })
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val (cover, draft) = klerk.read(author) { get(id).let { it.props.cover to it.props.draft!! } }
        val coverMeta = klerk.attachedData.getMetadata(cover.id, author)
        val draftMeta = klerk.attachedData.getMetadata(draft.id, author)

        val public = client.get(DefaultPathProvider().attachedDataPath(cover.id, coverMeta.hash))
        val private = client.get(DefaultPathProvider().attachedDataPath(draft.id, draftMeta.hash))

        assertEquals("public, max-age=60", public.headers[HttpHeaders.CacheControl])
        assertEquals(
            "private, max-age=${draftMeta.size}, immutable",
            private.headers[HttpHeaders.CacheControl],
        )
        klerk.meta.stop()
    }

    @Test
    fun `an actor who may not read private data gets the same answer as for data that does not exist`() =
        testApplication {
            setup(actor = stranger)
            klerk.meta.start(installShutdownHook = false)
            val id = createPublication()
            val draft = klerk.read(author) { get(id).props.draft!! }
            val meta = klerk.attachedData.getMetadata(draft.id, author)

            val denied = client.get(DefaultPathProvider().attachedDataPath(draft.id, meta.hash))
            val missing = client.get(DefaultPathProvider().attachedDataPath(draft.id, meta.hash).replace("/${draft.id}/", "/99999/"))

            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals(missing.status, denied.status)
            klerk.meta.stop()
        }

    @Test
    fun `the hash is part of the URL's identity`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val cover = klerk.read(author) { get(id).props.cover }

        val response = client.get(DefaultPathProvider().attachedDataPath(cover.id, "not-the-hash"))

        assertEquals(HttpStatusCode.NotFound, response.status)
        klerk.meta.stop()
    }

    @Test
    fun `data that has been deleted stops being served`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val cover = klerk.read(author) { get(id).props.cover }
        val meta = klerk.attachedData.getMetadata(cover.id, author)
        val path = DefaultPathProvider().attachedDataPath(cover.id, meta.hash)
        assertEquals(HttpStatusCode.OK, client.get(path).status)

        klerk.handle(
            Command(event = DeletePublication, model = id, params = null),
            author,
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()

        assertEquals(HttpStatusCode.NotFound, client.get(path).status)
        klerk.meta.stop()
    }

    @Test
    fun `the detail page links to the bytes of an attached property`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)
        val id = createPublication()
        val cover = klerk.read(author) { get(id).props.cover }
        val meta = klerk.attachedData.getMetadata(cover.id, author)

        val page = client.get("/publication/${id.value}").bodyAsText()

        assertTrue(
            page.contains("href=\"${DefaultPathProvider().attachedDataPath(cover.id, meta.hash)}\""),
            "expected a link to the cover in $page"
        )
        klerk.meta.stop()
    }

    @Test
    fun `an id that is not a number is a bad request`() = testApplication {
        setup()
        klerk.meta.start(installShutdownHook = false)

        assertEquals(HttpStatusCode.BadRequest, client.get("/_attached/nonsense/somehash").status)
        klerk.meta.stop()
    }
}
