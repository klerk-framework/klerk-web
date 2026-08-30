package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private suspend fun ApplicationCall.blockCtx(klerk: Klerk<Context, MyCollections>): Context = Context.system()

class PaginationTest {

    private fun klerk(): Klerk<Context, MyCollections> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        return Klerk.create(createConfig(collections), testSettings())
    }

    /** The `?cursor=` of the link whose button says [label], or null when the page does not offer that button. */
    private fun cursorOf(html: String, label: String): String? {
        // <a href="/author?cursor=XYZ"><button ...>Label</button></a>
        val pattern = Regex("""<a href="([^"]*)"><button[^>]*>$label</button></a>""")
        val href = pattern.find(html)?.groupValues?.get(1) ?: return null
        return Regex("""[?&]cursor=([^&"]*)""").find(href)?.groupValues?.get(1)
    }

    private fun rowIds(html: String): List<String> =
        Regex("""window\.location = '/author/([^']*)';""").findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `First Previous Next Last walk the whole list without skipping or repeating a row`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        generateSampleData(numberOfAuthors = 38, booksPerAuthor = 0, klerk = klerk)
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::blockCtx, canSeeAdminUI = { true })
        application { routing { klerkWebRoutes(klerkWeb) } }

        // Walk forward with Next.
        val seen = mutableListOf<String>()
        var url = "/author"
        var pages = 0
        while (true) {
            val html = client.get(url).bodyAsText()
            seen.addAll(rowIds(html))
            pages++
            assertTrue(pages < 20, "the traversal does not terminate")
            val next = cursorOf(html, "Next") ?: break
            url = "/author?cursor=$next"
        }
        assertEquals(3, pages, "38 authors at 15 per page")
        assertEquals(38, seen.size)
        assertEquals(38, seen.toSet().size, "no row may appear twice")

        // The last page offers First and Previous, but no Next or Last.
        val lastHtml = client.get(url).bodyAsText()
        assertNotNull(cursorOf(lastHtml, "First"))
        assertNotNull(cursorOf(lastHtml, "Previous"))
        assertNull(cursorOf(lastHtml, "Next"))
        assertNull(cursorOf(lastHtml, "Last"))

        // Previous walks back over the same rows.
        val backwards = mutableListOf<List<String>>()
        var html = lastHtml
        while (true) {
            backwards.add(rowIds(html))
            val previous = cursorOf(html, "Previous") ?: break
            html = client.get("/author?cursor=$previous").bodyAsText()
        }
        assertEquals(seen, backwards.reversed().flatten())
    }

    @Test
    fun `the first page offers no First or Previous, and Last jumps to the end`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        generateSampleData(numberOfAuthors = 38, booksPerAuthor = 0, klerk = klerk)
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::blockCtx, canSeeAdminUI = { true })
        application { routing { klerkWebRoutes(klerkWeb) } }

        val firstHtml = client.get("/author").bodyAsText()
        assertNull(cursorOf(firstHtml, "First"))
        assertNull(cursorOf(firstHtml, "Previous"))
        assertNotNull(cursorOf(firstHtml, "Next"))
        val last = assertNotNull(cursorOf(firstHtml, "Last"))

        val lastHtml = client.get("/author?cursor=$last").bodyAsText()
        assertEquals(8, rowIds(lastHtml).size, "38 authors, 15 per page, so the last page holds 8")
        assertNull(cursorOf(lastHtml, "Next"))
    }

    @Test
    fun `a list that fits on one page has no pagination links at all`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        generateSampleData(numberOfAuthors = 3, booksPerAuthor = 0, klerk = klerk)
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::blockCtx, canSeeAdminUI = { true })
        application { routing { klerkWebRoutes(klerkWeb) } }

        val html = client.get("/author").bodyAsText()
        assertEquals(3, rowIds(html).size)
        listOf("First", "Previous", "Next", "Last").forEach {
            assertNull(cursorOf(html, it), "a single-page list must not offer '$it'")
        }
    }

    @Test
    fun `paging keeps the other query parameters`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        generateSampleData(numberOfAuthors = 38, booksPerAuthor = 0, klerk = klerk)
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::blockCtx, canSeeAdminUI = { true })
        application { routing { klerkWebRoutes(klerkWeb) } }

        val html = client.get("/author?filterState=All").bodyAsText()
        val next = Regex("""<a href="([^"]*)"><button[^>]*>Next</button></a>""").find(html)?.groupValues?.get(1)
        assertNotNull(next)
        assertTrue(next.contains("filterState=All"), "the filter must survive paging, was '$next'")
        assertEquals(HttpStatusCode.OK, client.get(next).status)
    }

    @Test
    fun `a cursor that is not a cursor does not blow the page up`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        generateSampleData(numberOfAuthors = 20, booksPerAuthor = 0, klerk = klerk)
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::blockCtx, canSeeAdminUI = { true })
        application { routing { klerkWebRoutes(klerkWeb) } }

        listOf("", "hello", "!!!", "YToxLGY6Q1JFQVRFRF9BVA").forEach { cursor ->
            val response = client.get("/author?cursor=$cursor")
            assertEquals(HttpStatusCode.OK, response.status, "'$cursor' should not break the page")
            assertFalse(rowIds(response.bodyAsText()).isEmpty(), "'$cursor' should fall back to the first page")
        }
    }

    @Test
    fun `withQueryParam replaces one parameter and leaves the rest alone`() {
        assertEquals("/author?cursor=abc", withQueryParam("/author", "cursor", "abc"))
        assertEquals("/author?collection=x&cursor=abc", withQueryParam("/author?collection=x", "cursor", "abc"))
        assertEquals("/author?cursor=new", withQueryParam("/author?cursor=old", "cursor", "new"))
        // The parameter name also occurs in the path and in another parameter's value.
        assertEquals(
            "/cursor?filterString=cursor&cursor=new",
            withQueryParam("/cursor?filterString=cursor&cursor=old", "cursor", "new"),
        )
        assertEquals("/cursor?filterString=cursor&cursor=new", withQueryParam("/cursor?filterString=cursor", "cursor", "new"))
    }
}
