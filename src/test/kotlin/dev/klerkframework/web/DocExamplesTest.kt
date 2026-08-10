package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
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
        return Klerk.create(createConfig(MyCollections(bc, AuthorCollections(bc.all), ModelViews())))
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
            auditPath = "/admin/_audit",
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
                        table.build(klerk.config.views.authors.all, this, call)
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
