package dev.klerkframework.web

import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.EventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.collection.asSequence
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
        val klerk = Klerk.create(createConfig(collections).withPlugin(plugin), testSettings())
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

        val noteTemplate = FormTemplate(
            EventWithParameters(CreateNote.id, EventParameters(CreateNoteParams::class)),
            klerk,
            postPath = "/notes",
            pathProvider = DefaultPathProvider(),
            uploads = plugin,
        ) {
            text(CreateNoteParams::title)
            file(CreateNoteParams::content)
        }

        application {
            routing {
                uploadRoutes(support, plugin)
                post("/notes") {
                    val ctx = context()
                    when (val parsed = noteTemplate.parse(call, ctx)) {
                        is ParseResult.Forbidden -> call.respond(HttpStatusCode.Forbidden)
                        is ParseResult.Invalid -> call.respond(
                            HttpStatusCode.BadRequest,
                            parsed.problems.joinToString { it.endUserTranslatedMessage },
                        )
                        is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
                        is ParseResult.Parsed -> call.respondText("stored")
                    }
                }
                get("/new") {
                    val ctx = context()
                    val form = klerk.read(ctx) { template.build(call, null, this, translator = ctx.translation, context = ctx) }
                    call.respondHtml { body { eventForm(form) } }
                }
                post("/documents") {
                    val ctx = context()
                    when (val parsed = template.parse(call, ctx)) {
                        is ParseResult.Forbidden -> call.respond(HttpStatusCode.Forbidden)
                        is ParseResult.Invalid -> call.respond(
                            HttpStatusCode.BadRequest,
                            parsed.problems.joinToString { it.endUserTranslatedMessage },
                        )
                        is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
                        is ParseResult.Parsed -> {
                            val result = klerk.handle(
                                Command(CreateDocument, null, parsed.params),
                                ctx,
                                ProcessingOptions(parsed.key),
                            )
                            val id = requireNotNull(result.orThrow().primaryModel)
                            val blob = requireNotNull(klerk.read(ctx) { get(id).props.content })
                            call.respondText(String(klerk.attachedData.get(blob.id, ctx).readAllBytes()))
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
        // DocumentContent accepts anything, so there is nothing to narrow the picker to
        assertTrue(!body.contains("""accept="""), "an unconstrained property should not restrict the file picker")
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

    /**
     * The form validation script posts the whole form on every change, with `dryRun=true`. That must not turn the
     * upload into attached data: the file would be prepared twice - once here and once at submit - and the upload the
     * hidden field still points at would already be gone.
     */
    @Test
    fun `the validation dry run neither prepares the file nor consumes the upload`() = testApplication {
        val (klerk, plugin, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val upload = plugin.create(context(), "notes.txt", "text/plain", 11)
        plugin.append(context(), upload, 0, "hello world".byteInputStream())

        val dry = client.submitFormWithBinaryData(
            url = "/documents?dryRun=true&onlyErrors=true",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "My notes")
                append("content", upload.value.toString())
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }
        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        assertEquals(
            0,
            klerk.jobs.getAllJobs(context()).count { it.name.value == "klerk-process-attached-data" },
            "a dry run should not prepare the file",
        )

        val response = client.submitFormWithBinaryData(
            url = "/documents",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "My notes")
                append("content", upload.value.toString())
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("hello world", response.bodyAsText())
        assertEquals(
            1,
            klerk.jobs.getAllJobs(context()).count { it.name.value == "klerk-process-attached-data" },
            "the file should be prepared exactly once",
        )
        klerk.meta.stop()
    }

    /**
     * The validation script may catch the file input while the uploader script is still sending it, in which case the
     * bytes travel with the dry run too. Storing them would be a second upload of the same file.
     */
    @Test
    fun `a file posted with a dry run is not stored`() = testApplication {
        val (klerk, _, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val dry = client.submitFormWithBinaryData(
            url = "/documents?dryRun=true&onlyErrors=true",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "Still uploading")
                append("content", "posted directly".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"direct.txt\"")
                })
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }

        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        assertEquals(
            0,
            klerk.jobs.getAllJobs(context()).count { it.name.value == "klerk-process-attached-data" },
            "a dry run should not store the file",
        )
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

    /**
     * The path without JavaScript creates no Upload, so without this the property's limit would not be applied until
     * the claim — by which point the bytes are already in the blob store.
     */
    @Test
    fun `a file posted with the form is cut off at the size the property allows`() = testApplication {
        val (klerk, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        // SmallNote allows 10 bytes
        val response = client.submitFormWithBinaryData(
            url = "/notes",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "Too much")
                append("content", ByteArray(5000) { 'a'.code.toByte() }, Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"big.txt\"")
                })
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "larger than")
        klerk.meta.stop()
    }

    /**
     * The rule the documentation tells applications to write lives on CreateUpload, and the path without JavaScript
     * creates no upload. Dry-running the command is what keeps one definition of who may upload what.
     */
    @Test
    fun `the application's own upload rule decides on the path without JavaScript too`() = testApplication {
        val (klerk, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        // the rule in TestSetup refuses an upload declaring more than 10 MB for this actor
        val response = client.submitFormWithBinaryData(
            url = "/documents",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "Over the quota")
                append("content", ByteArray(10_100_000), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"big.bin\"")
                })
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        // Nothing was stored: the rule was consulted before the bytes were, so there is no blob to clean up either.
        assertTrue(
            klerk.read(context()) { views.documents.all.asSequence().toList() }.isEmpty(),
            "the document should not have been created",
        )
        klerk.meta.stop()
    }

    /**
     * The property's steps run before the command sees the file, whichever way it arrived. Here the script did the
     * uploading, so the file is already on the server when the form is submitted.
     */
    @Test
    fun `a file a step refuses cannot be submitted, the uploaded way`() = testApplication {
        val (klerk, plugin, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val upload = plugin.create(context(), "notes.txt", "text/plain", 12)
        plugin.append(context(), upload, 0, "has a virus!".byteInputStream())

        val response = client.post("/documents") {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    Csrf.TOKEN_NAME to csrfToken,
                    IDEMPOTENCE_KEY to CommandToken.simple().toString(),
                    "title" to "Infected",
                    "content" to upload.value.toString(),
                ).formUrlEncode()
            )
        }

        assertTrue(response.status.value >= 400, "a refused file must not be stored: ${response.status}")
        klerk.meta.stop()
    }

    @Test
    fun `a file a step refuses cannot be submitted, the posted way`() = testApplication {
        val (klerk, _, _) = setup()
        klerk.meta.start(installShutdownHook = false)

        val response = client.submitFormWithBinaryData(
            url = "/documents",
            formData = formData {
                append(Csrf.TOKEN_NAME, csrfToken)
                append(IDEMPOTENCE_KEY, CommandToken.simple().toString())
                append("title", "Infected")
                append("content", "has a virus!".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"notes.txt\"")
                })
            }
        ) {
            header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$csrfToken")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "infected")
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

    @Test
    fun `a constrained property narrows the file picker`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val plugin = UploadPlugin<Context, MyCollections>(Files.createTempDirectory("klerk-upload-accept"))
        val klerk = Klerk.create(createConfig(collections).withPlugin(plugin), testSettings())
        klerk.meta.start(installShutdownHook = false)

        // FlowerImage declares image types and a maximum size
        val template = FormTemplate(
            EventWithParameters(CreateFlower.id, EventParameters(CreateFlowerParams::class)),
            klerk,
            postPath = "/flowers",
            pathProvider = DefaultPathProvider(),
            uploads = plugin,
        ) {
            text(CreateFlowerParams::name)
            file(CreateFlowerParams::image)
        }
        application {
            routing {
                uploadRoutes(support = WebSupport(klerk, { _, _ -> context() }), plugin = plugin)
                get("/flowers/new") {
                    val ctx = context()
                    val form = klerk.read(ctx) {
                        template.build(call, null, this, translator = ctx.translation, context = ctx)
                    }
                    call.respondHtml { body { eventForm(form) } }
                }
            }
        }

        val body = client.get("/flowers/new").bodyAsText()

        assertContains(body, """accept="image/gif,image/jpeg,image/png,image/webp"""")
        assertContains(body, """data-klerk-max-size="10000000"""")
        klerk.meta.stop()
    }

}
