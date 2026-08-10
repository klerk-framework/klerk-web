package dev.klerkframework.web

import dev.klerkframework.klerk.EventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.web.config.*
import io.ktor.client.plugins.cookies.*
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
import kotlinx.html.h1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two forms on the same page must both work: unique element ids, and a CSRF token that is shared rather than
 * overwritten by whichever form was built last.
 */
class MultipleFormsTest {

    private fun template(klerk: Klerk<Context, MyCollections>) = FormTemplate(
        EventWithParameters(ChangeName.id, EventParameters(ChangeNameParams::class)),
        klerk,
        postPath = "/submit",
        pathProvider = DefaultPathProvider(),
    ) {
        remaining()
    }

    private fun setup(): Klerk<Context, MyCollections> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        return Klerk.create(createConfig(collections))
    }

    @Test
    fun `two forms on one page get unique ids and share one csrf token`() = testApplication {
        val klerk = setup()
        klerk.meta.start()
        val template = template(klerk)

        application {
            routing {
                get("/two") {
                    val context = Context.system()
                    val forms = klerk.read(context) {
                        listOf(
                            template.build(call, null, this, translator = context.translation, context = context),
                            template.build(call, null, this, translator = context.translation, context = context),
                        )
                    }
                    call.respondHtml {
                        body {
                            h1 { +"Two forms" }
                            forms.forEach { eventForm(it) }
                        }
                    }
                }
            }
        }

        val response = client.get("/two")
        val body = response.bodyAsText()

        val formIds = Regex("""<form[^>]*\sid="([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(2, formIds.size, "Expected two forms")
        assertEquals(formIds.toSet().size, formIds.size, "Form ids must be unique")

        val inputIds = Regex("""<input[^>]*\sid="([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(
            inputIds.toSet().size,
            inputIds.size,
            "Input ids must be unique across forms, but got: $inputIds"
        )

        // One token in the response, repeated in both forms - not one Set-Cookie per form.
        val tokens = Regex("""name="${Regex.escape(Csrf.TOKEN_NAME)}" value="([^"]+)"""")
            .findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(2, tokens.size)
        assertEquals(1, tokens.toSet().size, "Both forms must carry the same token")
        assertEquals(1, response.headers.getAll(HttpHeaders.SetCookie).orEmpty().size)

        // No global handler, and every form is bound by the script instead.
        assertFalse(body.contains("""onchange="validate()""""), "Should not use a global onchange handler")
        assertTrue(body.contains("data-klerk-form"))
    }

    @Test
    fun `a submission without a valid csrf token is rejected`() = testApplication {
        val klerk = setup()
        klerk.meta.start()
        val template = template(klerk)

        application {
            routing {
                get("/one") {
                    val context = Context.system()
                    val form = klerk.read(context) {
                        template.build(call, null, this, translator = context.translation, context = context)
                    }
                    call.respondHtml { body { eventForm(form) } }
                }
                post("/submit") {
                    when (val result = template.parse(call, Context.system())) {
                        is ParseResult.Forbidden -> FormTemplate.respondForbidden(call)
                        is ParseResult.Invalid -> FormTemplate.respondInvalid(result, call)
                        is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
                        is ParseResult.Parsed -> call.respondText("ok")
                    }
                }
            }
        }

        val client = createClient { install(HttpCookies) }
        client.get("/one")

        val rejected = client.submitForm(
            url = "/submit",
            formParameters = parameters { append(Csrf.TOKEN_NAME, "wrong") },
        )
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
    }
}

private fun assertFalse(actual: Boolean, message: String) = assertTrue(!actual, message)
