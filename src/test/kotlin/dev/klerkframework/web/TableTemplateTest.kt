package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.web.config.Author
import dev.klerkframework.web.config.AuthorCollections
import dev.klerkframework.web.config.BookCollections
import dev.klerkframework.web.config.Context
import dev.klerkframework.web.config.CreateAuthor
import dev.klerkframework.web.config.FirstName
import dev.klerkframework.web.config.LastName
import dev.klerkframework.web.config.MyCollections
import dev.klerkframework.web.config.createAstridParameters
import dev.klerkframework.web.config.createAuthorAstrid
import dev.klerkframework.web.config.createAuthorJKRowling
import dev.klerkframework.web.config.createConfig
import dev.klerkframework.web.config.testSettings
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.html.body
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun ApplicationCall.systemCtx(klerk: Klerk<Context, MyCollections>): Context = Context.system()

/** Rowling has no score, which is the point: a nullable column. */
private val scores = mapOf("Lindgren" to 53, "Svensson" to 7)

class TableTemplateTest {

    private val columns = listOf(
        Column.property(Author::lastName),
        Column.property(Author::firstName),
        Column.value<Author, Int?>("Score") { scores[it.props.lastName.value] },
        Column.state(),
    )

    private suspend fun klerk(): Klerk<Context, MyCollections> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val klerk = Klerk.create(
            createConfig(MyCollections(bc, AuthorCollections(bc.all), ModelViews())),
            testSettings(),
        )
        klerk.meta.start()
        createAuthorJKRowling(klerk)
        createAuthorAstrid(klerk)
        klerk.handle(
            Command(
                CreateAuthor,
                createAstridParameters.copy(
                    firstName = FirstName("Bertil"),
                    lastName = LastName("Svensson"),
                    nullableFirstName = null,
                ),
            ),
            Context.system(),
        ).getOrThrow()
        return klerk
    }

    private fun ApplicationTestBuilder.serve(
        klerk: Klerk<Context, MyCollections>,
        context: Context = Context.system(),
        template: TableTemplate<Author, Context, MyCollections> =
            TableTemplate(klerk, Author::class, WebSupport(klerk, ApplicationCall::systemCtx), columns),
    ) {
        val support = WebSupport(klerk, ApplicationCall::systemCtx)
        application {
            routing {
                get("/authors") {
                    val table = klerk.read(context) { template.build(views.authors.all, this, call) }
                    support.respondPage(call, "Authors") { modelTable(table) }
                }
                get("/plain") {
                    val table = klerk.read(context) { template.build(views.authors.all, this, call) }
                    call.respond(klerk.read(context) { html { body { modelTable(table) } } })
                }
            }
        }
    }

    /** The first cell of each row, i.e. the last names. */
    private fun rows(html: String): List<String> =
        Regex("""<tr[^>]*onclick[^>]*>\s*<td><a href="[^"]*"[^>]*>([^<]*)</a>""").findAll(html)
            .map { it.groupValues[1] }.toList()

    private suspend fun ApplicationTestBuilder.rowsOf(url: String): List<String> {
        val response = client.get(url)
        assertEquals(HttpStatusCode.OK, response.status, url)
        return rows(response.bodyAsText())
    }

    private fun headerLink(html: String, header: String): String =
        Regex("""<th><a href="([^"]*)">$header( [▲▼])?</a>""").find(html)!!.groupValues[1].replace("&amp;", "&")

    @Test
    fun `clicking a header cycles ascending, descending, unsorted`() = testApplication {
        val klerk = klerk()
        serve(klerk)

        val unsorted = client.get("/authors").bodyAsText()
        val ascendingUrl = headerLink(unsorted, "Last name")
        assertEquals("/authors?sort=lastName", ascendingUrl)
        val ascending = client.get(ascendingUrl).bodyAsText()
        assertEquals(listOf("Lindgren", "Rowling", "Svensson"), rows(ascending))
        assertTrue(ascending.contains("Last name ▲"))

        val descendingUrl = headerLink(ascending, "Last name")
        assertEquals("/authors?sort=lastName&dir=desc", descendingUrl)
        assertEquals(listOf("Svensson", "Rowling", "Lindgren"), rowsOf(descendingUrl))

        val descending = client.get(descendingUrl).bodyAsText()
        assertEquals("/authors", headerLink(descending, "Last name"))
    }

    @Test
    fun `each row has one row link for keyboard navigation`() = testApplication {
        val klerk = klerk()
        serve(klerk)

        val html = client.get("/authors").bodyAsText()
        assertTrue(html.contains("data-klerk-list"))
        assertEquals(3, Regex("data-klerk-row-link").findAll(html).count())
        assertTrue(html.contains("""tabindex="-1""""), "the other cell links should not be Tab stops")
        assertTrue(html.contains("klerkList.js"), "the navigation script should be included")
    }

    @Test
    fun `empty values sort last in both directions`() = testApplication {
        val klerk = klerk()
        serve(klerk)

        assertEquals(listOf("Svensson", "Lindgren", "Rowling"), rowsOf("/authors?sort=score"))
        assertEquals(listOf("Lindgren", "Svensson", "Rowling"), rowsOf("/authors?sort=score&dir=desc"))
    }

    @Test
    fun `values in one column are ORed, columns are ANDed`() = testApplication {
        val klerk = klerk()
        serve(klerk)

        assertEquals(setOf("Rowling", "Lindgren"), rowsOf("/authors?f.score=null&f.score=53").toSet())
        assertEquals(setOf("Rowling", "Lindgren"), rowsOf("/authors?f.score=null,+53").toSet())
        assertEquals(setOf("Lindgren"), rowsOf("/authors?f.score.min=10").toSet())
        assertEquals(setOf("Svensson"), rowsOf("/authors?f.score.max=10").toSet())
        assertEquals(setOf("Rowling", "Lindgren"), rowsOf("/authors?f.score=null&f.score.min=10").toSet())
        assertEquals(setOf("Svensson"), rowsOf("/authors?f.lastName.contains=SVEN").toSet())
        assertEquals(setOf("Lindgren"), rowsOf("/authors?f.score=null,53&f.lastName.contains=lind").toSet())
        assertEquals(setOf("Rowling"), rowsOf("/authors?f.lastName=Rowling,Nobody").toSet())
        // An empty field in the submitted form is no filter.
        assertEquals(3, rowsOf("/authors?f.score=&f.score.min=&f.lastName.contains=").size)
    }

    @Test
    fun `active filters are listed with a link that removes them`() = testApplication {
        val klerk = klerk()
        serve(klerk)

        val html = client.get("/authors?sort=lastName&f.score=null&f.lastName.contains=n").bodyAsText()
        assertTrue(html.contains("Score: empty"))
        assertTrue(html.contains("Last name: contains 'n'"))
        assertTrue(html.contains("""<a href="/authors?sort=lastName&amp;f.lastName.contains=n" title="Remove"""))
        assertTrue(html.contains("""<a href="/authors?sort=lastName">Clear all filters</a>"""))
        assertTrue(html.contains("<details open"), "the panel is open while filtering")
    }

    @Test
    fun `the filter form has the right control for each column`() = testApplication {
        val klerk = klerk()
        serve(klerk)

        val html = client.get("/authors?sort=lastName&f.score=null,53").bodyAsText()
        assertTrue(html.contains("""<input type="hidden" name="sort" value="lastName">"""), "keeps the sort")
        assertTrue(html.contains("""name="f.score" id="f-score-any" value="53""""), html)
        assertTrue(html.contains("""<input type="checkbox" name="f.score" value="null" checked"""))
        assertTrue(html.contains("""<input type="number" name="f.score.min""""))
        assertTrue(html.contains("""<input type="text" name="f.lastName.contains""""))
        assertTrue(html.contains("""<input type="checkbox" name="f.state" value="Amateur""""))
        assertFalse(html.contains("""name="f.lastName" value="null""""), "a non-nullable column has no Empty")
    }

    @Test
    fun `a value the actor may not read matches no filter and sorts last`() = testApplication {
        val klerk = klerk()
        // Only the system may read the first name Astrid.
        serve(klerk, context = Context.authenticationIdentity())

        assertEquals(listOf("Svensson", "Rowling", "Lindgren"), rowsOf("/authors?sort=firstName"))
        assertEquals(listOf("Rowling", "Svensson", "Lindgren"), rowsOf("/authors?sort=firstName&dir=desc"))
        assertEquals(emptyList(), rowsOf("/authors?f.firstName=Astrid"))
        assertEquals(setOf("Svensson"), rowsOf("/authors?f.firstName.contains=t").toSet())
        assertFalse(client.get("/authors").bodyAsText().contains("Astrid"))
    }

    @Test
    fun `an invalid parameter is a 400 with a link to the page without it`() = testApplication {
        val klerk = klerk()
        serve(klerk)

        suspend fun check(url: String, expectedLink: String, message: String) {
            for (path in listOf("/authors", "/plain")) {
                val response = client.get(path + url)
                assertEquals(HttpStatusCode.BadRequest, response.status, path + url)
                val html = response.bodyAsText()
                assertTrue(html.contains(message), html)
                assertTrue(html.contains("""<a href="$path$expectedLink">"""), html)
            }
        }

        check("?sort=lastName&f.nope=1", "?sort=lastName", "There is no column 'nope'")
        check("?sort=lastName&f.score=abc&cursor=xyz", "?sort=lastName", "'abc' is not a valid value for Score")
        check("?f.state=Sleeping", "", "'Sleeping' is not a valid value for State")
        check("?f.lastName.min=a", "", "cannot be filtered with 'min'")
        check("?sort=firstName&dir=sideways", "?sort=firstName", "'asc' or 'desc'")
        check("?sort=id", "", "There is no column 'id' to sort on")
        check("?f.score.contains=5", "", "cannot be filtered with 'contains'")
    }

    @Test
    fun `a prefix keeps the parameters of two tables apart, and defaultSort applies without a sort parameter`() =
        testApplication {
            val klerk = klerk()
            val template = TableTemplate(
                klerk,
                Author::class,
                WebSupport(klerk, ApplicationCall::systemCtx),
                columns,
                defaultSort = TableSort("lastName", ascending = false),
                paramPrefix = "a-",
            )
            serve(klerk, template = template)

            assertEquals(listOf("Svensson", "Rowling", "Lindgren"), rowsOf("/authors"))
            assertEquals(listOf("Svensson", "Rowling", "Lindgren"), rowsOf("/authors?sort=score"))
            assertEquals(listOf("Svensson", "Lindgren", "Rowling"), rowsOf("/authors?a-sort=score"))
            assertEquals(setOf("Lindgren"), rowsOf("/authors?a-f.score=53&f.score=7").toSet())
            assertEquals(listOf("Svensson"), rowsOf("/authors?a-view=v.Author.greatAuthors&view=nope"))
        }

    @Test
    fun `header controls put sort arrows and a filter dialog by each header`() = testApplication {
        val klerk = klerk()
        val template = TableTemplate(
            klerk,
            Author::class,
            WebSupport(klerk, ApplicationCall::systemCtx),
            columns,
            controls = TableControls.Header,
        )
        serve(klerk, template = template)

        val html = client.get("/authors?sort=lastName&f.score=null").bodyAsText()
        assertFalse(html.contains("<summary>Filters</summary>"), "no panel")
        // The views are links that keep the sort and filters; the current one is marked, not linked.
        assertFalse(html.contains("viewselect"))
        assertTrue(html.contains("""<nav aria-label="View">"""), html)
        assertTrue(html.contains("""<strong aria-current="page">All</strong>"""), html)
        val link = "<a href=\"/authors?sort=lastName&amp;f.score=null&amp;view=v.Author.greatAuthors\">" +
            "Great authors</a>"
        assertTrue(html.contains(link), html)
        val great = client.get("/authors?view=v.Author.greatAuthors").bodyAsText()
        assertTrue(great.contains("""<strong aria-current="page">Great authors</strong>"""), great)
        assertEquals(listOf("Svensson"), rows(great))
        // The active arrow removes the sort, the other one sorts.
        assertTrue(
            html.contains(
                """<a href="/authors?f.score=null" title="Sort ascending" style="text-decoration: none;" data-active="">↑""",
            ),
        )
        assertTrue(
            html.contains(
                """<a href="/authors?f.score=null&amp;sort=lastName&amp;dir=desc" title="Sort descending" style="text-decoration: none; opacity: 0.35;">⇣<""",
            ),
        )
        assertTrue(client.get("/authors?sort=lastName&dir=desc").bodyAsText().contains("""opacity: 0.35;">⇡<"""))
        assertTrue(
            client.get(
                "/authors?sort=lastName&dir=desc",
            ).bodyAsText().contains("""style="text-decoration: none;" data-active="">↓<"""),
        )
        assertTrue(html.contains("""title="Filtered: empty" popovertarget="filter-score""""))
        assertTrue(html.contains("""<div id="filter-score" popover="">"""))
        // The controls are marked for CSS, and the dialog is outside them.
        val scoreHeader = html.substringBefore("<div id=\"filter-score\"").substringAfterLast("<th>")
        assertTrue(scoreHeader.trim().endsWith("Score"), "the header text comes after the controls")
        val controls = scoreHeader
            .substringAfter("<span data-table-controls=\"\" style=\"display: block; white-space: nowrap;\">")
            .substringBefore("</span>")
        assertTrue(controls.contains("""<button type="button" title="Filtered: empty""""))
        assertFalse(controls.contains("popover=\"\""))
        // The dialog keeps the sort and replaces only its own column's filter.
        val dialog = html.substringAfter("""<div id="filter-score" popover="">""").substringBefore("</form>")
        assertTrue(dialog.contains("""<input type="hidden" name="sort" value="lastName">"""))
        assertFalse(dialog.contains("""type="hidden" name="f.score""""))
        assertTrue(dialog.contains("""<a href="/authors?sort=lastName">Remove filter</a>"""))
        assertTrue(html.contains("Score: empty"), "active filters are still listed")
        // Sorting and filtering work as in the panel.
        assertEquals(listOf("Lindgren", "Rowling"), rowsOf("/authors?sort=lastName&f.score=null,53"))
    }

    @Test
    fun `columns must have unique keys and defaultSort must name a sortable column`() = runBlockingTest {
        val klerk = klerk()
        val support = WebSupport(klerk, ApplicationCall::systemCtx)
        assertFailsWith<IllegalArgumentException> {
            TableTemplate(klerk, Author::class, support, columns + Column.property(Author::lastName))
        }
        assertFailsWith<IllegalArgumentException> {
            TableTemplate(klerk, Author::class, support, columns, defaultSort = TableSort("nope"))
        }
        assertFailsWith<IllegalArgumentException> {
            TableTemplate(klerk, Author::class, support, Column.defaults(), defaultSort = TableSort("id"))
        }
    }

    @Test
    fun `the generated list page shows every property and filters on state`() = testApplication {
        val klerk = klerk()
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::systemCtx, canSeeAdminUI = { true })
        application { routing { klerkWebRoutes(klerkWeb, setOf(Author::class)) } }

        val html = client.get("/author").bodyAsText()
        listOf("ID", "First name", "Last name", "State", "Created", "Updated").forEach {
            assertTrue(html.contains(">$it"), "column $it")
        }
        assertTrue(html.contains("""href="/author?sort=lastName""""))
        assertTrue(html.contains("""<input type="checkbox" name="f.state" value="Amateur""""))

        assertEquals(HttpStatusCode.BadRequest, client.get("/author?f.state=Sleeping").status)

        assertEquals(HttpStatusCode.OK, client.get("/author?view=v.Author.all").status)
        for (view in listOf("nope", "v.Author.nope", "v.Book.all")) {
            val response = client.get("/author?view=$view&sort=lastName&cursor=x")
            assertEquals(HttpStatusCode.BadRequest, response.status, view)
            val body = response.bodyAsText()
            assertTrue(body.contains("There is no view '$view'"), view)
            assertTrue(body.contains("""<a href="/author?sort=lastName">"""), view)
        }
    }
}

private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
