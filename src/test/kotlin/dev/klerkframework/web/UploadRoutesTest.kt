package dev.klerkframework.web

import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
import dev.klerkframework.web.upload.UploadPlugin
import dev.klerkframework.web.upload.uploadRoutes
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tus endpoints, over HTTP. What matters here is what a real client sees: where to resume, what happens when it
 * gets that wrong, and that none of it works without the CSRF token.
 */
class UploadRoutesTest {

    private val csrfHeader = Csrf.TOKEN_NAME
    private val token = "test-csrf-token"

    private fun ApplicationTestBuilder.setup(): Pair<Klerk<Context, MyCollections>, UploadPlugin<Context, MyCollections>> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val dir = Files.createTempDirectory("klerk-upload-routes")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val plugin = UploadPlugin<Context, MyCollections>(dir)
        val klerk = Klerk.create(createConfig(collections).withPlugin(plugin))
        val support = WebSupport(klerk, { _, _ -> Context(CustomIdentity(id = null, externalId = 1)) })
        application {
            routing {
                uploadRoutes(support, plugin)
            }
        }
        return klerk to plugin
    }

    /** The double-submit pair: the same value in the cookie and in the header. */
    private fun HttpRequestBuilder.withCsrf() {
        header(csrfHeader, token)
        header(HttpHeaders.Cookie, "$csrfHeader=$token")
    }

    private fun metadata(filename: String, contentType: String): String {
        val encoder = Base64.getEncoder()
        return "filename ${encoder.encodeToString(filename.toByteArray())}," +
                "contentType ${encoder.encodeToString(contentType.toByteArray())}"
    }

    @Test
    fun `a file is created, uploaded in two chunks and can be resumed in between`() = testApplication {
        val (klerk, plugin) = setup()
        klerk.meta.start(installShutdownHook = false)
        val content = "a resumable upload, one chunk at a time".toByteArray()

        val created = client.post("/uploads") {
            withCsrf()
            header("Tus-Resumable", "1.0.0")
            header("Upload-Length", content.size.toString())
            header("Upload-Metadata", metadata("notes.txt", "text/plain"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val location = assertNotNull(created.headers[HttpHeaders.Location])

        val first = client.patch(location) {
            withCsrf()
            header("Tus-Resumable", "1.0.0")
            header("Upload-Offset", "0")
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody(content.copyOfRange(0, 10))
        }
        assertEquals(HttpStatusCode.NoContent, first.status)
        assertEquals("10", first.headers["Upload-Offset"])

        // a client that lost its connection asks where it got to
        val head = client.head(location) { header("Tus-Resumable", "1.0.0") }
        assertEquals("10", head.headers["Upload-Offset"])
        assertEquals(content.size.toString(), head.headers["Upload-Length"])
        assertEquals("no-store", head.headers[HttpHeaders.CacheControl])

        val second = client.patch(location) {
            withCsrf()
            header("Tus-Resumable", "1.0.0")
            header("Upload-Offset", "10")
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody(content.copyOfRange(10, content.size))
        }
        assertEquals(HttpStatusCode.NoContent, second.status)
        assertEquals(content.size.toString(), second.headers["Upload-Offset"])

        val id = location.substringAfterLast("/").toInt()
        assertEquals(
            String(content),
            String(plugin.read(Context(CustomIdentity(id = null, externalId = 1)), dev.klerkframework.klerk.ModelID(id)).readAllBytes())
        )
        klerk.meta.stop()
    }

    @Test
    fun `a chunk at the wrong offset gets a conflict and is told where to resume`() = testApplication {
        val (klerk, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val location = assertNotNull(client.post("/uploads") {
            withCsrf()
            header("Upload-Length", "20")
        }.headers[HttpHeaders.Location])

        client.patch(location) {
            withCsrf()
            header("Upload-Offset", "0")
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody("12345".toByteArray())
        }

        val conflict = client.patch(location) {
            withCsrf()
            header("Upload-Offset", "17")
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody("678".toByteArray())
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("5", conflict.headers["Upload-Offset"], "the client is told where the upload really is")
        klerk.meta.stop()
    }

    @Test
    fun `a chunk that does not match its checksum is rejected and rolled back`() = testApplication {
        val (klerk, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val location = assertNotNull(client.post("/uploads") {
            withCsrf()
            header("Upload-Length", "10")
        }.headers[HttpHeaders.Location])

        val corrupted = client.patch(location) {
            withCsrf()
            header("Upload-Offset", "0")
            header("Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256("something else")))
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody("abcde".toByteArray())
        }
        assertEquals(460, corrupted.status.value)
        assertEquals("0", client.head(location).headers["Upload-Offset"], "a bad chunk leaves nothing behind")

        val good = client.patch(location) {
            withCsrf()
            header("Upload-Offset", "0")
            header("Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256("abcde")))
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody("abcde".toByteArray())
        }
        assertEquals(HttpStatusCode.NoContent, good.status)
        klerk.meta.stop()
    }

    @Test
    fun `nothing can be started or appended without the csrf token`() = testApplication {
        val (klerk, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val withoutToken = client.post("/uploads") { header("Upload-Length", "5") }
        assertEquals(HttpStatusCode.Forbidden, withoutToken.status)

        val location = assertNotNull(client.post("/uploads") {
            withCsrf()
            header("Upload-Length", "5")
        }.headers[HttpHeaders.Location])

        val patchWithoutToken = client.patch(location) {
            header("Upload-Offset", "0")
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody("hello".toByteArray())
        }
        assertEquals(HttpStatusCode.Forbidden, patchWithoutToken.status)
        klerk.meta.stop()
    }

    @Test
    fun `an upload that does not exist is a plain not found`() = testApplication {
        val (klerk, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val response = client.head("/uploads/123456")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().isEmpty(), "nothing about the upload is disclosed")
        klerk.meta.stop()
    }

    @Test
    fun `a small file can be sent with the creation request`() = testApplication {
        val (klerk, plugin) = setup()
        klerk.meta.start(installShutdownHook = false)

        val created = client.post("/uploads") {
            withCsrf()
            header("Upload-Length", "5")
            contentType(ContentType.parse("application/offset+octet-stream"))
            setBody("hello".toByteArray())
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("5", created.headers["Upload-Offset"])

        val id = assertNotNull(created.headers[HttpHeaders.Location]).substringAfterLast("/").toInt()
        assertEquals(
            "hello",
            String(plugin.read(Context(CustomIdentity(id = null, externalId = 1)), dev.klerkframework.klerk.ModelID(id)).readAllBytes())
        )
        klerk.meta.stop()
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
}
