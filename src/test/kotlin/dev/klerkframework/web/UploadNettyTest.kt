package dev.klerkframework.web

import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.web.config.AuthorCollections
import dev.klerkframework.web.config.BookCollections
import dev.klerkframework.web.config.Context
import dev.klerkframework.web.config.CreateDocument
import dev.klerkframework.web.config.CreateDocumentParams
import dev.klerkframework.web.config.MyCollections
import dev.klerkframework.web.config.createConfig
import dev.klerkframework.web.config.testSettings
import dev.klerkframework.web.upload.UploadPlugin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The path without JavaScript, over a real socket: the test engine hands the body over differently than Netty. */
class UploadNettyTest {

    @Test
    fun `a file posted without JavaScript arrives with its bytes on a real server`() = runBlocking<Unit> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val plugin = UploadPlugin<Context, MyCollections>(Files.createTempDirectory("klerk-upload-netty"))
        val klerk: Klerk<Context, MyCollections> = Klerk.create(createConfig(collections).withPlugin(plugin), testSettings())
        klerk.meta.start(installShutdownHook = false)
        val context = Context(CustomIdentity(id = null, externalId = 7))
        val template = FormTemplate(
            EventWithParameters(CreateDocument.id, CreateDocumentParams::class),
            klerk,
            postPath = "/documents",
            pathProvider = DefaultPathProvider(),
            uploads = plugin,
        ) {
            text(CreateDocumentParams::title)
            file(CreateDocumentParams::content)
        }
        val token = "netty-test-token"

        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/documents") {
                    when (val parsed = template.parse(call, context)) {
                        is ParseResult.Parsed -> {
                            val id = requireNotNull(
                                klerk.handle(Command(CreateDocument, parsed.params), context, ProcessingOptions(parsed.key))
                                    .getOrThrow().primaryModel,
                            )
                            val blob = requireNotNull(klerk.read(context) { get(id).props.content })
                            call.respondText(String(klerk.attachedData.get(blob.id, context).readAllBytes()))
                        }
                        else -> call.respond(HttpStatusCode.BadRequest, parsed.toString())
                    }
                }
            }
        }.start(wait = false)
        val client = HttpClient(CIO)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val response = client.submitFormWithBinaryData(
                url = "http://localhost:$port/documents",
                formData = formData {
                    append(Csrf.TOKEN_NAME, token)
                    append("idempotence-key", CommandToken.simple().toString())
                    append("title", "Over a socket")
                    append(
                        "content",
                        "the bytes must arrive".toByteArray(),
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/plain")
                            append(HttpHeaders.ContentDisposition, "filename=\"socket.txt\"")
                        },
                    )
                },
            ) {
                header(HttpHeaders.Cookie, "${Csrf.TOKEN_NAME}=$token")
            }
            assertEquals("the bytes must arrive", response.bodyAsText())
        } finally {
            client.close()
            server.stop(0, 0)
            klerk.meta.stop()
        }
    }
}
