package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun ApplicationCall.blockCtx(klerk: Klerk<Context, MyCollections>): Context = Context.system()

/** A PathProvider that says Book has no detail view. */
private class NoBookDetails : PathProvider by DefaultPathProvider() {
    private val delegate = DefaultPathProvider()
    override fun pathForItem(kClass: KClass<out Any>, id: ModelID<*>): String? =
        if (kClass == Book::class) null else delegate.pathForItem(kClass, id)

    override fun pathForItem(kClass: KClass<out Any>, id: String): String? =
        if (kClass == Book::class) null else delegate.pathForItem(kClass, id)
}

class BuildingBlocksTest {

    private fun klerk(): Klerk<Context, MyCollections> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        return Klerk.create(createConfig(collections))
    }

    @Test
    fun `generateRoutes can be limited to some models`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::blockCtx, canSeeAdminUI = { true })

        application {
            routing { apply(klerkWeb.generateRoutes(filter = { it.kClass == Author::class })) }
        }

        assertEquals(HttpStatusCode.OK, client.get("/author").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/book").status)
    }

    @Test
    fun `a model without a detail path gets no route and no links to it`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        val author = createAuthorJKRowling(klerk)
        val book = createBookHarryPotter1(klerk, author)
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::blockCtx, canSeeAdminUI = { true }, pathProvider = NoBookDetails())

        application { routing { apply(klerkWeb.generateRoutes()) } }

        // No route was registered for the detail page...
        assertEquals(HttpStatusCode.NotFound, client.get("/book/${book.value}").status)

        // ...and the list does not link to it either, rather than producing a dead link.
        val list = client.get("/book").bodyAsText()
        assertFalse(list.contains("""href="/book/${book.value}""""), "Should not link to a page that has no route")
        assertTrue(list.contains("Harry Potter"), "The book should still be listed")
    }

    @Test
    fun `table columns are values that can be replaced`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        createAuthorJKRowling(klerk)
        val support = WebSupport(klerk, ApplicationCall::blockCtx)

        val columns = listOf(
            Column<Author>("Surname") { model -> +model.props.lastName.value },
        ) + Column.defaults<Author>().filter { it.header == "State" }

        application {
            routing {
                get("/authors") {
                    val context = Context.system()
                    val table = klerk.read(context) {
                        TableTemplate(klerk, Author::class, support, columns)
                            .build(klerk.config.views.authors.all, this, call)
                    }
                    call.respondPage(support.layout, "Authors") { apply(table.render()) }
                }
            }
        }

        val body = client.get("/authors").bodyAsText()
        assertTrue(body.contains("<th>Surname</th>"), "Expected the custom column")
        assertTrue(body.contains("<th>State</th>"), "Expected the kept default column")
        assertFalse(body.contains("<th>Created</th>"), "Expected the dropped default column to be gone")
        assertTrue(body.contains("Rowling"))
    }
}
