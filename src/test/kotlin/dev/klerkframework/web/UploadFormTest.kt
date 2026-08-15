package dev.klerkframework.web

import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.EventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.web.config.*
import dev.klerkframework.web.upload.UploadPlugin
import dev.klerkframework.web.upload.uploadRoutes
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.html.body
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `FormTemplate.file()` from both ends: the form a browser gets, and what happens to each of the two ways it can come
 * back — an upload id when the script did the work, and the file itself when there was no script.
 */
class UploadFormTest {

    private val csrfToken = "form-test-token"
    private val actor = CustomIdentity(id = null, externalId = 7)

    private fun context() = Context(actor)

    private fun ApplicationTestBuilder.setup(): Triple<Klerk<Context, MyCollections>, UploadPlugin<Context, MyCollections>, FormTemplate<CreateDocumentParams, Context, MyCollections>> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val plugin = UploadPlugin<Context, MyCollections>(Files.createTempDirectory("klerk-upload-form"))
        val klerk = Klerk.create(createConfig(collections).withPlugin(plugin))
        val support = WebSupport(klerk, { _, _ -> context() })

        val template = FormTemplate(
            EventWithParameters(CreateDocument.id, EventParameters(CreateDocumentParams::class)),
            klerk,
            postPath = "/documents",
            pathProvider = DefaultPathProvider(),
            uploads = plugin,
        ) {
            text(CreateDocumentParams::title)
            file(CreateDocumentParams::content)
        }

        application {
            routing {
                uploadRoutes(support, plugin)
                get("/new") {
                    val ctx = context()
                    val form = klerk.read(ctx) { template.build(call, null, this, translator = ctx.translation, context = ctx) }
                    call.respondHtml { body { eventForm(form) } }
                }
                post("/documents") {
                    val ctx = context()
                    when (val parsed = template.parse(call, ctx)) {
                        is ParseResult.Forbidden -> call.respond(HttpStatusCode.Forbidden)
                        is ParseResult.Invalid -> call.respond(HttpStatusCode.BadRequest, parsed.problems.toString())
                        is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
                        is ParseResult.Parsed -> {
                            val result = klerk.handle(
                                Command(CreateDocument, null, parsed.params),
                                ctx,
                                ProcessingOptions(parsed.key),
                            )
                            val id = requireNotNull(result.orThrow().primaryModel)
                            val blob = requireNotNull(klerk.read(ctx) { get(id).props.content })
                            call.respondText(String(klerk.attachedData.get(blob, ctx).readAllBytes()))
                        }
                    }
                }
            }
        }
        return Triple(klerk, plugin, template)
    }

    @Test
    fun `the form offers a file input, a hidden field for the upload and the uploader script`() = testApplication {
        val (klerk, _, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val body = client.get("/new").bodyAsText()

        assertContains(body, """enctype="multipart/form-data"""", message = "a submit without JavaScript needs it")
        assertContains(body, """data-klerk-upload-path="/uploads"""")
        assertContains(body, """type="file"""")
        assertContains(body, """data-klerk-file="content"""")
        assertContains(body, """data-klerk-upload-id="content"""")
        assertTrue(body.contains("klerkUpload.js"), "the uploader script should be included")
        // The file input carries the name, so that a browser without JavaScript still sends the bytes.
        assertContains(body, """name="content"""")
        klerk.meta.stop()
    }

    @Test
    fun `a submitted upload id becomes the attached data of the new model`() = testApplication {
        val (klerk, plugin, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val upload = plugin.create(context(), "notes.txt", "text/plain", 11)
        plugin.append(context(), upload, 0, "hello world".byteInputStream())

        val response = client.post("/documents") {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    Csrf.TOKEN_NAME to csrfToken,
                    IDEMPOTENCE_KEY to CommandToken.simple().toString(),
                    "title" to "My notes",
                    "content" to upload.value.toString(),
                ).formUrlEncode()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("hello world", response.bodyAsText())
        klerk.meta.stop()
    }

    /**
     * What a browser actually sends after the script has uploaded the file: the form is multipart, because the
     * enctype is on it for the no-script case, and the file field arrives as a hidden field holding the upload id
     * rather than as a file part.
     */
    @Test
    fun `a multipart submission carrying an upload id resolves it just like a urlencoded one`() = testApplication {
        val (klerk, plugin, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val upload = plugin.create(context(), "notes.txt", "text/plain", 16)
        plugin.append(context(), upload, 0, "uploaded by js!".byteInputStream())
        plugin.append(context(), upload, 15, "\n".byteInputStream())

        val response = client.submitFormWithBinaryData(
            url = "/documents",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "From the browser")
                append("content", upload.value.toString())
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("uploaded by js!\n", response.bodyAsText())
        klerk.meta.stop()
    }

    @Test
    fun `without JavaScript the file is posted with the form and uploaded in one request`() = testApplication {
        val (klerk, _, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val response = client.submitFormWithBinaryData(
            url = "/documents",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "No script here")
                append("content", "posted directly".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"direct.txt\"")
                })
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("posted directly", response.bodyAsText())
        klerk.meta.stop()
    }

    @Test
    fun `a submission without the csrf token is refused before the upload is touched`() = testApplication {
        val (klerk, plugin, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val upload = plugin.create(context(), "notes.txt", "text/plain", 5)
        plugin.append(context(), upload, 0, "hello".byteInputStream())

        val response = client.post("/documents") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    IDEMPOTENCE_KEY to CommandToken.simple().toString(),
                    "title" to "No token",
                    "content" to upload.value.toString(),
                ).formUrlEncode()
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        // The upload is still there: a request that fails the check must not have consumed it.
        assertEquals(5, plugin.offsetOf(context(), upload))
        klerk.meta.stop()
    }

    @Test
    fun `an upload belonging to somebody else cannot be named in a form`() = testApplication {
        val (klerk, plugin, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val somebodyElse = Context(CustomIdentity(id = null, externalId = 99))
        val upload = plugin.create(somebodyElse, "theirs.txt", "text/plain", 5)
        plugin.append(somebodyElse, upload, 0, "mine!".byteInputStream())

        val response = client.post("/documents") {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    Csrf.TOKEN_NAME to csrfToken,
                    IDEMPOTENCE_KEY to CommandToken.simple().toString(),
                    "title" to "Not mine",
                    "content" to upload.value.toString(),
                ).formUrlEncode()
            )
        }

        assertNotNull(response.status)
        assertTrue(response.status.value >= 400, "naming another actor's upload must not work")
        klerk.meta.stop()
    }
}
