package dev.klerkframework.web

import dev.klerkframework.klerk.EventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.web.config.*
import io.ktor.client.request.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyReferenceOptionsTest {

    @Test
    @Disabled("Fix usage of Clock in Klerk")
    fun `form with non-nullable ModelID field and no instances renders error instead of form`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val klerk = Klerk.create(createConfig(collections))
        klerk.meta.start()

        // No authors are created, so the non-nullable author field has no options
        val template = EventFormTemplate(
            EventWithParameters(CreateBook.id, EventParameters(CreateBookParams::class)),
            klerk, "/create-book",
            classProvider = null,
        ) {
            remaining()
        }

        var html = ""
        application {
            routing {
                get("/test") {
                    val context = Context.unauthenticated()
                    html = klerk.read(context) {
                        val form = template.build(
                            call = call,
                            params = null,
                            reader = this,
                            translator = context.translation
                        )
                        createHTML().div { form.render(this) }
                    }
                }
            }
        }
        client.get("/test")

        assertTrue(html.contains("Error:"), "Expected error message in HTML but got: $html")
        assertFalse(html.contains("<form"), "Expected no form element in HTML but got: $html")
    }

    @Test
    @Disabled("Fix usage of Clock in Klerk")
    fun `form with nullable ModelID field and no instances renders form normally`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val klerk = Klerk.create(createConfig(collections))
        klerk.meta.start()

        // No authors are created, but favouriteColleague is nullable so it should still render
        val template = EventFormTemplate(
            EventWithParameters(CreateAuthor.id, EventParameters(CreateAuthorParams::class)),
            klerk, "/create-author",
            classProvider = null,
        ) {
            remaining()
        }

        var html = ""
        application {
            routing {
                get("/test") {
                    val context = Context.unauthenticated()
                    html = klerk.read(context) {
                        val form = template.build(
                            call = call,
                            params = null,
                            reader = this,
                            translator = context.translation
                        )
                        createHTML().div { form.render(this) }
                    }
                }
            }
        }
        client.get("/test")

        assertFalse(html.contains("Error:"), "Expected no error message in HTML but got: $html")
        assertTrue(html.contains("<form"), "Expected form element in HTML but got: $html")
    }
}
